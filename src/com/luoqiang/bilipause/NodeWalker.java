package com.luoqiang.bilipause;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 带预算的节点树遍历。
 * B 站播放器页节点很多（弹幕 + 播放器控件），必须限深限量，否则一次扫描能吃掉几十毫秒。
 */
final class NodeWalker {

    static final int MAX_NODES = 1300;
    static final int MAX_DEPTH = 32;
    private static final int MAX_CHILDREN = 80;
    private static final int DUMP_MAX_DEPTH = 40;
    private static final int DUMP_MAX_LINES = 1600;

    private NodeWalker() {
    }

    static final class Item {
        final AccessibilityNodeInfo node;
        final int depth;
        final String cls;
        final String id;
        final String idTail;
        final String text;
        final String desc;
        final Rect rect;
        final boolean clickable;

        Item(AccessibilityNodeInfo n, int depth) {
            this.node = n;
            this.depth = depth;
            this.cls = n.getClassName() == null ? "" : n.getClassName().toString();
            this.id = NodeUtil.id(n);
            this.idTail = NodeUtil.tail(this.id);
            this.text = NodeUtil.text(n);
            this.desc = NodeUtil.desc(n);
            this.rect = NodeUtil.rect(n);
            this.clickable = NodeUtil.clickable(n);
        }

        String label() {
            return text != null && text.trim().length() > 0 ? text.trim() : (desc == null ? "" : desc.trim());
        }
    }

    /** 可能是"子节点还没挂上"的容器：面积够大且是 ViewGroup/Compose 一类 */
    private static boolean looksContainer(Item it) {
        String c = it.cls;
        if (c == null || c.length() == 0) return false;
        if (c.contains("ComposeView") || c.contains("ViewGroup") || c.contains("FrameLayout")
                || c.contains("LinearLayout") || c.contains("RelativeLayout")
                || c.contains("RecyclerView") || c.contains("ConstraintLayout")) {
            return it.rect.width() >= 80 && it.rect.height() >= 80;
        }
        return false;
    }

    static List<Item> flatten(AccessibilityNodeInfo root) {
        List<Item> out = new ArrayList<Item>(128);
        if (root == null) return out;
        List<Item> queue = new ArrayList<Item>(256);
        queue.add(new Item(root, 0));
        int head = 0;
        while (head < queue.size() && out.size() < MAX_NODES) {
            Item it = queue.get(head++);
            out.add(it);
            if (it.depth >= MAX_DEPTH) continue;
            int n = it.node.getChildCount();
            // Compose / 容器节点的子节点是异步挂上来的：扫描这一刻可能还是 0 个，
            // 几十毫秒后（比如同一拍里稍晚生成的 dump）就有了。
            // 实测就是"dump 里有『后将展示广告』、扫描却没找到"，错过 2~3 秒的提示条。
            if (n == 0 && it.depth < MAX_DEPTH && looksContainer(it)) {
                try {
                    it.node.refresh();
                } catch (Exception ignore) {
                }
                n = it.node.getChildCount();
            }
            if (n > MAX_CHILDREN) n = MAX_CHILDREN;
            for (int i = 0; i < n; i++) {
                if (queue.size() >= MAX_NODES) break;
                AccessibilityNodeInfo c;
                try {
                    c = it.node.getChild(i);
                } catch (Exception e) {
                    c = null;
                }
                if (c != null) queue.add(new Item(c, it.depth + 1));
            }
        }
        return out;
    }

    /** 把当前界面导成可读文本，用于排查规则为什么没命中 */
    static String dump(AccessibilityNodeInfo root, String note) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        if (note != null && note.length() > 0) sb.append("  ").append(note);
        sb.append('\n');
        if (root == null) {
            sb.append("(root == null)\n");
            return sb.toString();
        }
        sb.append("package=").append(root.getPackageName()).append('\n');
        int[] lines = new int[]{0};
        walk(root, 0, sb, lines);
        if (lines[0] >= DUMP_MAX_LINES) sb.append("... (truncated)\n");
        return sb.toString();
    }

    private static void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] lines) {
        if (n == null || lines[0] >= DUMP_MAX_LINES) return;
        lines[0]++;
        for (int i = 0; i < depth && i < 20; i++) sb.append("  ");
        Rect r = NodeUtil.rect(n);
        sb.append(n.getClassName()).append(' ');
        String id = NodeUtil.id(n);
        if (id.length() > 0) sb.append("id=").append(NodeUtil.tail(id)).append(' ');
        String t = NodeUtil.text(n);
        if (t.trim().length() > 0) sb.append("text=\"").append(oneLine(t)).append("\" ");
        String d = NodeUtil.desc(n);
        if (d.trim().length() > 0) sb.append("desc=\"").append(oneLine(d)).append("\" ");
        sb.append(NodeUtil.clickable(n) ? "clickable " : "");
        sb.append('[').append(r.left).append(',').append(r.top).append(',')
                .append(r.right).append(',').append(r.bottom).append("]");
        sb.append('\n');
        if (depth >= DUMP_MAX_DEPTH) return;
        int n2 = n.getChildCount();
        for (int i = 0; i < n2 && lines[0] < DUMP_MAX_LINES; i++) {
            AccessibilityNodeInfo c;
            try {
                c = n.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            if (c != null) walk(c, depth + 1, sb, lines);
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ');
        return x.length() > 120 ? x.substring(0, 120) + "…" : x;
    }
}
