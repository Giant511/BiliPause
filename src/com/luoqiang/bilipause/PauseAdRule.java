package com.luoqiang.bilipause;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 暂停广告识别。
 *
 * 真机取证（B 站 9.10.0 / Android 15）后的实际结构：
 *   卡片容器 View [33,871,1227,2726]
 *     ├ TextView "美团神券"
 *     ├ View clickable  → View desc="dislike"    （⋯ 更多）
 *     ├ View clickable  → View desc="close"      （★ 要点的 ×，本身不可点，父节点可点）
 *     └ TextView "广告"  （角标，在卡片左下）
 *
 * 所以识别链是：
 *   1) 找"广告"角标 → 沿候选节点祖先链向上找一张"卡片级"容器（面积 8%~85% 屏，且该矩形内有角标）
 *   2) 找有 close 语义的小控件（id 含 close/skip，或文案/描述是 close/关闭/跳过/×）
 *   3) 要求候选确实落在这张卡片里、且贴右上角，才给足分
 *   4) 安全红线过滤后，够分才点
 */
final class PauseAdRule {

    /** 常规路径的出手阈值 */
    private static final int SCORE_ACT = 75;
    /** 激进模式（既没角标也没广告容器）的阈值，更高更保守 */
    private static final int SCORE_ACT_AGGRESSIVE = 95;

    private static final String[] AD_LABELS = {"广告", "推广", "赞助"};

    /** 精确匹配。绝不能用 startsWith("关闭")，否则播放器的"关闭弹幕"会被误点 */
    private static final String[] CLOSE_LABELS = {
            "关闭", "关闭广告", "关闭此广告", "跳过", "跳过广告", "×", "✕", "╳", "收起",
            "close", "skip", "dismiss"
    };
    private static final String[] CLOSE_ID_WORDS = {"close", "skip", "dismiss"};

    static final class Scan {
        Rect adRect;
        AccessibilityNodeInfo close;
        int score;
        String why;
    }

    private PauseAdRule() {
    }

    /**
     * 一手：掐死在展示前。
     * 暂停后 B 站会先弹一条提示「1秒后将展示广告　取消」，点「取消」广告就根本不出现。
     *
     * 安全性：「取消」本身是危险词（别的弹窗里可能是取消业务操作），
     * 所以必须同时满足：整棵树里存在"X秒后将展示广告"这句话，且「取消」就在这句话同一行附近。
     * 这个组合几乎唯一，可以放心点。
     */
    static Scan scanPreCancel(AdGuardService svc, AccessibilityNodeInfo root) {
        int sw = svc.screenWidth();
        int sh = svc.screenHeight();
        float d = svc.density();

        List<NodeWalker.Item> items = NodeWalker.flatten(root);

        NodeWalker.Item hint = null;
        for (int i = 0; i < items.size(); i++) {
            String l = items.get(i).label();
            if (l.contains("后将展示广告") || l.contains("后将展示推广")) {
                hint = items.get(i);
                break;
            }
        }
        if (hint == null) return null;

        int hintCy = (hint.rect.top + hint.rect.bottom) / 2;
        NodeWalker.Item cancel = null;
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            String l = it.label().trim();
            if (!"取消".equals(l) && !"取消展示".equals(l)) continue;
            int cy = (it.rect.top + it.rect.bottom) / 2;
            if (Math.abs(cy - hintCy) > NodeUtil.dp(48, d)) continue;      // 必须同一行
            if (it.rect.left < hint.rect.left - NodeUtil.dp(8, d)) continue; // 必须在提示文字右侧
            if (it.rect.left - hint.rect.right > NodeUtil.dp(200, d)) continue;
            if (Safety.reject(it, sw, sh) != null) continue;
            cancel = it;
            break;
        }
        if (cancel == null) return null;

        Scan s = new Scan();
        s.close = cancel.node;
        s.score = 100;
        s.why = "广告前提示条的「取消」";
        return s;
    }

    static Scan scan(AdGuardService svc, AccessibilityNodeInfo root) {        int sw = svc.screenWidth();
        int sh = svc.screenHeight();
        float d = svc.density();

        List<NodeWalker.Item> items = NodeWalker.flatten(root);
        if (items.isEmpty()) return null;

        // 已知的广告容器（B 站部分广告位 id 直接带 ad 词）
        List<Rect> adRects = new ArrayList<Rect>();
        for (int i = 0; i < items.size(); i++) {
            if (isAdContainer(items.get(i), sw, sh)) adRects.add(items.get(i).rect);
        }

        boolean aggressive = Prefs.aggressive();
        Scan best = null;
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            boolean byId = matchCloseId(it.idTail);
            boolean byLabel = matchCloseLabel(it.label());
            if (!byId && !byLabel) continue;

            String rej = Safety.reject(it, sw, sh);
            if (rej != null) continue;

            int cx = (it.rect.left + it.rect.right) / 2;
            int cy = (it.rect.top + it.rect.bottom) / 2;

            int score = 0;
            StringBuilder why = new StringBuilder();
            if (byId) {
                score += 40;
                why.append("id:").append(it.idTail).append(' ');
            }
            if (byLabel) {
                score += 25;
                why.append("label:").append(it.label()).append(' ');
            }
            if (it.clickable) score += 10;

            int minSide = NodeUtil.dp(12, d);
            int maxSide = NodeUtil.dp(56, d);
            int w = it.rect.width();
            int h = it.rect.height();
            if (w >= minSide && w <= maxSide && h >= minSide && h <= maxSide) {
                score += 15;
                why.append("小尺寸 ");
            }

            // 卡片级容器：祖先链上含"广告"角标、面积 8%~85% 屏
            Rect host = findAdCardHost(it, items, sw, sh);
            if (host == null) {
                for (int k = 0; k < adRects.size(); k++) {
                    if (NodeUtil.contains(adRects.get(k), cx, cy)) {
                        host = adRects.get(k);
                        break;
                    }
                }
            }

            boolean nearCorner = nearAnyTopRight(it.rect, adRects, NodeUtil.dp(72, d));
            if (host == null && !nearCorner) {
                if (!aggressive) continue;
            } else {
                if (host != null) {
                    score += 20;
                    why.append("在广告卡片内 ");
                    if (nearTopRight(it.rect, host, NodeUtil.dp(72, d))) {
                        score += 40;
                        why.append("贴卡片右上角 ");
                    }
                } else {
                    score += 30;
                    why.append("贴广告角 ");
                }
            }

            int penalty = Safety.positionPenalty(
                    it.rect.left, it.rect.top, it.rect.right, it.rect.bottom, sw, sh, d);
            score += penalty;
            if (penalty < 0) why.append("位置扣分").append(penalty).append(' ');

            int need = (host == null && !nearCorner) ? SCORE_ACT_AGGRESSIVE : SCORE_ACT;
            if (score < need) continue;

            if (best == null || score > best.score) {
                best = new Scan();
                best.close = it.node;
                best.adRect = host != null ? host : (adRects.isEmpty() ? null : adRects.get(0));
                best.score = score;
                best.why = why.toString().trim();
            }
        }
        return best;
    }

    /**
     * 从候选节点往上找"卡片级"容器：面积 8%~85% 屏，且该矩形内存在"广告"角标。
     * 找到就说明这个候选确实落在一张广告卡片里，而不是播放器控制条上的普通按钮。
     */
    private static Rect findAdCardHost(NodeWalker.Item item, List<NodeWalker.Item> items, int sw, int sh) {
        AccessibilityNodeInfo p = item.node;
        for (int up = 0; up < 8; up++) {
            try {
                p = p.getParent();
            } catch (Exception e) {
                return null;
            }
            if (p == null) return null;
            Rect pr = NodeUtil.rect(p);
            float ratio = NodeUtil.ratio(pr, sw, sh);
            if (ratio < 0.08f) continue;
            if (ratio > 0.85f) return null;
            for (int k = 0; k < items.size(); k++) {
                NodeWalker.Item m = items.get(k);
                if (!isAdMarker(m)) continue;
                int mx = (m.rect.left + m.rect.right) / 2;
                int my = (m.rect.top + m.rect.bottom) / 2;
                if (NodeUtil.contains(pr, mx, my)) return pr;
            }
        }
        return null;
    }

    /** "广告"角标：文案就是广告/推广/赞助这一类短标签 */
    private static boolean isAdMarker(NodeWalker.Item it) {
        String l = it.label();
        if (l.length() == 0 || l.length() > 6) return false;
        for (int i = 0; i < AD_LABELS.length; i++) {
            if (l.equals(AD_LABELS[i])) return true;
        }
        return false;
    }

    private static boolean containsAdToken(String idTail) {
        String[] tk = NodeUtil.tokens(idTail);
        for (int i = 0; i < tk.length; i++) {
            if (tk[i].equals("ad") || tk[i].equals("ads")) return true;
        }
        return false;
    }

    private static boolean isAdContainer(NodeWalker.Item it, int sw, int sh) {
        float ratio = NodeUtil.ratio(it.rect, sw, sh);
        if (ratio < 0.05f) return false;
        if (containsAdToken(it.idTail)) return true;
        String label = it.label();
        if (label.length() > 0 && ratio >= 0.10f) {
            for (int i = 0; i < AD_LABELS.length; i++) {
                if (label.contains(AD_LABELS[i])) return true;
            }
        }
        return false;
    }

    private static boolean matchCloseId(String idTail) {
        String[] tk = NodeUtil.tokens(idTail);
        for (int i = 0; i < tk.length; i++) {
            for (int j = 0; j < CLOSE_ID_WORDS.length; j++) {
                if (tk[i].equals(CLOSE_ID_WORDS[j])) return true;
            }
        }
        return false;
    }

    private static boolean matchCloseLabel(String label) {
        if (label == null) return false;
        String t = label.trim();
        if (t.length() == 0) return false;
        if (exactIn(t)) return true;
        String stripped = stripTail(t);
        return stripped.length() != t.length() && exactIn(stripped);
    }

    private static boolean exactIn(String t) {
        for (int i = 0; i < CLOSE_LABELS.length; i++) {
            if (t.equalsIgnoreCase(CLOSE_LABELS[i])) return true;
        }
        return false;
    }

    /** 去掉末尾的数字/秒数，例如 "关闭广告3s" -> "关闭广告" */
    private static String stripTail(String t) {
        int end = t.length();
        while (end > 0) {
            char c = t.charAt(end - 1);
            if (Character.isDigit(c) || c == 's' || c == 'S' || c == ' ' || c == '秒'
                    || c == '.' || c == '\u3000') {
                end--;
            } else {
                break;
            }
        }
        return t.substring(0, end).trim();
    }

    private static boolean nearTopRight(Rect c, Rect host, int tol) {
        if (host == null) return false;
        int dx = Math.abs(c.right - host.right);
        int dy = Math.abs(c.top - host.top);
        return dx <= tol && dy <= tol && c.left >= host.left - tol && c.bottom <= host.bottom + tol;
    }

    private static boolean nearAnyTopRight(Rect c, List<Rect> hosts, int tol) {
        for (int i = 0; i < hosts.size(); i++) {
            if (nearTopRight(c, hosts.get(i), tol)) return true;
        }
        return false;
    }
}
