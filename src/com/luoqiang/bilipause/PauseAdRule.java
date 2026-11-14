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

    /**
     * 播放器下方那个常驻广告位（B 站叫 underplayer，节点 id=ad_tint_frame）。
     * 它不是暂停广告，只是视频页常驻的横幅；暂停时它甚至是 0 宽 0 高的。
     * 关键：它的右上角离"评论详情"面板的 × 很近，一旦把它当锚点，
     * 打开评论区时那个 × 就会被误点 —— 实测就是这么把评论区叉掉的。
     */
    private static final String UNDERPLAYER = "underplayer_container";

    private static volatile String diag = "";

    static String lastDiag() {
        return diag;
    }

    /** 最近一次扫描看到的 close 语义候选数量（即使没命中）。用于"广告还在入场"时补扫 */
    private static volatile int candCount = 0;

    static int lastCandCount() {
        return candCount;
    }

    /** 这一帧是否"值得存"：出现广告角标、倒计时文案或 close 语义控件 */
    private static volatile boolean interesting = false;

    static boolean lastInteresting() {
        return interesting;
    }

    /**
     * 播放器是否已暂停。B 站暂停后控制条会出现 id=gemini_halfscreen_play 的按钮（desc="播放"）。
     * 暂停是"暂停广告"的唯一触发时机，识别到就进入密集采样窗口。
     */
    /**
     * 这一帧是不是播放器页。用控件 id 判断，不依赖"进入页面的那个事件"——
     * 服务重绑、重装、或在暂停状态下重新连接时不会有新的页面事件，靠事件记状态会永远停在 false。
     */
    static boolean looksPlayer(List<NodeWalker.Item> items) {
        for (int i = 0; i < items.size(); i++) {
            String id = items.get(i).idTail;
            // 只用播放器自身才有的控件，避免首页/动态页被误判成播放器页
            if ("video_area_float_layer_container".equals(id)
                    || id.startsWith("gemini_halfscreen")
                    || id.startsWith("bbplayer_halfscreen")) {
                return true;
            }
        }
        return false;
    }

    static boolean looksPaused(List<NodeWalker.Item> items) {
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            if ("gemini_halfscreen_play".equals(it.idTail)) return true;
            String lb = it.label();
            if ("播放".equals(lb) || "继续播放".equals(lb)) return true;
        }
        return false;
    }

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
        return scanPreCancel(svc, root, NodeWalker.flatten(root));
    }

    static Scan scanPreCancel(AdGuardService svc, AccessibilityNodeInfo root, List<NodeWalker.Item> items) {
        int sw = svc.screenWidth();
        int sh = svc.screenHeight();
        float d = svc.density();

        NodeWalker.Item hint = null;
        for (int i = 0; i < items.size(); i++) {
            String l = items.get(i).label();
            if (l.contains("后将展示广告") || l.contains("后将展示推广")) {
                hint = items.get(i);
                break;
            }
        }
        if (hint == null) return null;

        // Compose 版提示条会把「3秒后将展示广告」和「取消」合并进同一个语义节点，
        // 这时整条提示就是一个可点节点，直接点它即可（点了就是取消）。
        String hl = hint.label();
        if (hl.contains("取消") && hint.clickable && Safety.reject(hint, sw, sh) == null) {
            Scan s = new Scan();
            s.close = hint.node;
            s.score = 100;
            s.why = "提示条合并节点（含取消）";
            return s;
        }

        int hintCy = (hint.rect.top + hint.rect.bottom) / 2;
        NodeWalker.Item cancel = null;
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            String l = it.label().trim();
            boolean isCancel = "取消".equals(l) || "取消展示".equals(l) || "取消广告".equals(l)
                    || "关闭".equals(l) || "关闭广告".equals(l)
                    || (l.contains("取消") && l.length() <= 8);
            if (!isCancel) continue;
            int cy = (it.rect.top + it.rect.bottom) / 2;
            if (Math.abs(cy - hintCy) > NodeUtil.dp(48, d)) continue;
            if (it.rect.left < hint.rect.left - NodeUtil.dp(8, d)) continue;
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

    static Scan scan(AdGuardService svc, AccessibilityNodeInfo root) {
        return scan(svc, root, NodeWalker.flatten(root));
    }

    static Scan scan(AdGuardService svc, AccessibilityNodeInfo root, List<NodeWalker.Item> items) {
        int sw = svc.screenWidth();
        int sh = svc.screenHeight();
        float d = svc.density();
        if (items.isEmpty()) return null;

        // 已知的广告容器（B 站部分广告位 id 直接带 ad 词）
        List<Rect> adRects = new ArrayList<Rect>();
        boolean hit = false;
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            if (isAdContainer(it, sw, sh)) adRects.add(it.rect);
            if (!hit) {
                String lb = it.label();
                String tl = it.idTail.toLowerCase();
                // 常驻广告条（播放器下方那条）每帧都在，算进来会让抓取模式每帧落盘，
                // 落盘把扫描线程拖住，广告真出现时反而来不及 —— 这里必须排除。
                boolean worthNode = lb.contains("秒后") || lb.equals("取消")
                        || tl.contains("close") || !adSlotSubtree(it) && lb.contains("广告");
                if (worthNode) hit = true;
            }
        }
        interesting = hit;

        // 可信的"广告"角标：排除掉常驻广告条（ad_tint_frame / underplayer）里的角标。
        // 否则内容区里任何面板的 × 都会因为"内容区里存在广告角标"而被当成广告关闭按钮。
        List<NodeWalker.Item> markers = new ArrayList<NodeWalker.Item>();
        for (int i = 0; i < items.size(); i++) {
            NodeWalker.Item it = items.get(i);
            if (isAdMarker(it) && !adSlotSubtree(it)) markers.add(it);
        }

        boolean aggressive = Prefs.aggressive();
        StringBuilder dg = new StringBuilder(64);
        dg.append("items=").append(items.size()).append(" adRects=").append(adRects.size());
        int cands = 0;
        String bestInfo = "";
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
            Rect host = findAdCardHost(it, markers, sw, sh);
            if (host == null) {
                for (int k = 0; k < adRects.size(); k++) {
                    if (NodeUtil.contains(adRects.get(k), cx, cy)) {
                        host = adRects.get(k);
                        break;
                    }
                }
            }

            // 先计数：只要树里有 close 语义控件就算"可能正在入场"。
            // 以前这行写在下面 continue 之后，导致广告角标还没挂上的那几帧 cands=0，
            // 补扫永远不触发 —— 命不命中全看运气，这就是"有时灵有时不灵"的根因。
            cands++;
            if (bestInfo.length() == 0) {
                bestInfo = "|" + it.label() + "@" + it.rect.left + "," + it.rect.top + " sc=" + score
                        + " host=" + (host == null ? "null" : host.toShortString());
            }

            // 必须真的落在某个广告卡片里才动手。
            // 早期版本还允许"贴近广告容器角落"就出手，结果把评论详情面板的 × 当成广告关闭点了。
            if (host == null) {
                if (!aggressive) continue;
            } else {
                score += 20;
                why.append("在广告卡片内 ");
                if (nearTopRight(it.rect, host)) {
                    score += 40;
                    why.append("贴卡片右上角 ");
                }
            }

            // 位置扣分只用来排除"播放器控制条上的按钮"，不能把真广告卡片的 × 一起毙掉：
            // 竖屏小窗时广告卡片就贴在屏幕底部，旧规则直接扣 60 分，永远够不到阈值。
            int penalty = Safety.positionPenalty(
                    it.rect.left, it.rect.top, it.rect.right, it.rect.bottom, sw, sh, d);
            if (host != null) penalty = Math.max(penalty, -20);
            score += penalty;
            if (penalty < 0) why.append("位置扣分").append(penalty).append(' ');

            int need = host == null ? SCORE_ACT_AGGRESSIVE : SCORE_ACT;
            if (score < need) continue;

            if (best == null || score > best.score) {
                best = new Scan();
                best.close = it.node;
                best.adRect = host != null ? host : (adRects.isEmpty() ? null : adRects.get(0));
                best.score = score;
                best.why = why.toString().trim();
            }
        }
        candCount = cands;
        diag = dg.append(" cands=").append(cands).append(' ').append(bestInfo).toString();
        return best;
    }

    /**
     * 从候选节点往上找"卡片级"容器：面积 8%~85% 屏，且该矩形内存在"广告"角标。
     * 找到就说明这个候选确实落在一张广告卡片里，而不是播放器控制条上的普通按钮。
     */
    /**
     * 找候选所属的"广告卡片"。
     *
     * 优先：候选与某个「广告」角标的最小公共祖先 —— 这跟画幅、播放器占比无关，
     * 只要 × 和角标在同一张卡片里就成立。
     * 退路：沿祖先链找第一个矩形内包含角标的容器。
     * 两种都不再用"面积必须 ≥8% 屏"这种绝对条件（竖屏小窗时卡片会被压到 8% 以下）。
     */
    private static Rect findAdCardHost(NodeWalker.Item item, List<NodeWalker.Item> markers, int sw, int sh) {
        if (markers.isEmpty()) return null;

        // 祖先链（含自身），顺带确认没走进常驻广告位
        List<AccessibilityNodeInfo> chain = new ArrayList<AccessibilityNodeInfo>(10);
        AccessibilityNodeInfo p = item.node;
        chain.add(p);
        for (int up = 0; up < 9; up++) {
            try {
                p = p.getParent();
            } catch (Exception e) {
                break;
            }
            if (p == null) break;
            String tail = NodeUtil.tail(NodeUtil.id(p));
            if (UNDERPLAYER.equalsIgnoreCase(tail) || "ad_tint_frame".equalsIgnoreCase(tail)) return null;
            chain.add(p);
        }

        // 最小公共祖先
        Rect best = null;
        for (int k = 0; k < markers.size(); k++) {
            AccessibilityNodeInfo q = markers.get(k).node;
            for (int up = 0; up < 9 && q != null; up++) {
                if (chainContains(chain, q)) {
                    Rect r = NodeUtil.rect(q);
                    float ratio = NodeUtil.ratio(r, sw, sh);
                    // 只做合理性上下限：太小是角标本身，太大是整页
                    if (ratio >= 0.015f && ratio <= 0.95f) {
                        if (best == null || NodeUtil.area(r) < NodeUtil.area(best)) best = r;
                    }
                    break;
                }
                try {
                    q = q.getParent();
                } catch (Exception e) {
                    break;
                }
            }
        }
        if (best != null) return best;

        // 退路：祖先链里第一个"矩形包含角标"的容器
        for (int i = 1; i < chain.size(); i++) {
            Rect pr = NodeUtil.rect(chain.get(i));
            float ratio = NodeUtil.ratio(pr, sw, sh);
            if (ratio < 0.015f) continue;
            if (ratio > 0.95f) break;
            for (int k = 0; k < markers.size(); k++) {
                NodeWalker.Item m = markers.get(k);
                int mx = (m.rect.left + m.rect.right) / 2;
                int my = (m.rect.top + m.rect.bottom) / 2;
                if (NodeUtil.contains(pr, mx, my)) return pr;
            }
        }
        return null;
    }

    /** 节点同一性：先用 equals，失败再比"类名+id+矩形" */
    private static boolean chainContains(List<AccessibilityNodeInfo> chain, AccessibilityNodeInfo n) {
        for (int i = 0; i < chain.size(); i++) {
            AccessibilityNodeInfo c = chain.get(i);
            if (c == n) return true;
            try {
                if (c.equals(n)) return true;
            } catch (Exception ignore) {
            }
            if (sameNode(c, n)) return true;
        }
        return false;
    }

    private static boolean sameNode(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        if (a == null || b == null) return false;
        String ia = NodeUtil.id(a);
        String ib = NodeUtil.id(b);
        if (!ia.equals(ib)) return false;
        String ca = String.valueOf(a.getClassName());
        String cb = String.valueOf(b.getClassName());
        if (!ca.equals(cb)) return false;
        return NodeUtil.rect(a).equals(NodeUtil.rect(b));
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
        if (ratio < 0.02f) return false;
        if (underPlayerSlot(it)) return false;
        if (containsAdToken(it.idTail)) return true;
        String label = it.label();
        if (label.length() > 0 && ratio >= 0.05f) {
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

    /**
     * 候选是否在 host 的右上角区域 —— 用 host 自身宽高归一化，不用固定 dp。
     * 竖屏视频可以调播放器高度，广告卡片会被压扁或拉高，固定容差在这里必然漂。
     * fx = 候选右边缘距 host 右边缘的比例；fy = 候选上边缘距 host 上边缘的比例。
     */
    private static boolean nearTopRight(Rect c, Rect host) {
        if (host == null || c == null) return false;
        int w = host.width();
        int h = host.height();
        if (w <= 0 || h <= 0) return false;
        float fx = (host.right - c.right) / (float) w;
        float fy = (c.top - host.top) / (float) h;
        // 右上角一带：横向 0~20%、纵向 0~30%，允许略微出界（阴影/描边）
        return fx >= -0.03f && fx <= 0.20f && fy >= -0.03f && fy <= 0.30f
                && c.left >= host.left && c.bottom <= host.bottom;
    }

    /** 是否位于常驻广告位（ad_tint_frame / underplayer_container）子树里 */
    private static boolean adSlotSubtree(NodeWalker.Item it) {
        if ("ad_tint_frame".equalsIgnoreCase(it.idTail)) return true;
        AccessibilityNodeInfo p = it.node;
        for (int i = 0; i < 6; i++) {
            try {
                p = p.getParent();
            } catch (Exception e) {
                return false;
            }
            if (p == null) return false;
            String tail = NodeUtil.tail(NodeUtil.id(p));
            if (UNDERPLAYER.equalsIgnoreCase(tail) || "ad_tint_frame".equalsIgnoreCase(tail)) return true;
        }
        return false;
    }

    /** 自己或祖先是不是播放器下方那个常驻广告位 */
    private static boolean underPlayerSlot(NodeWalker.Item it) {
        if (UNDERPLAYER.equalsIgnoreCase(it.idTail)) return true;
        AccessibilityNodeInfo p = it.node;
        for (int i = 0; i < 6; i++) {
            try {
                p = p.getParent();
            } catch (Exception e) {
                return false;
            }
            if (p == null) return false;
            if (UNDERPLAYER.equalsIgnoreCase(NodeUtil.tail(NodeUtil.id(p)))) return true;
        }
        return false;
    }
}
