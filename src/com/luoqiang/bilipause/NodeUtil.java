package com.luoqiang.bilipause;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** 节点取值与几何小工具。所有方法对 null 安全。 */
final class NodeUtil {

    private NodeUtil() {
    }

    static String id(AccessibilityNodeInfo n) {
        CharSequence c = n.getViewIdResourceName();
        return c == null ? "" : c.toString();
    }

    static String text(AccessibilityNodeInfo n) {
        CharSequence c = n.getText();
        return c == null ? "" : c.toString();
    }

    static String desc(AccessibilityNodeInfo n) {
        CharSequence c = n.getContentDescription();
        return c == null ? "" : c.toString();
    }

    /** B 站的 id 形如 tv.danmaku.bili:id/ad_close，判定只看最后一段 */
    static String tail(String id) {
        if (id == null || id.length() == 0) return "";
        int i = id.lastIndexOf('/');
        return (i >= 0 && i + 1 < id.length()) ? id.substring(i + 1) : id;
    }

    static String label(AccessibilityNodeInfo n) {
        String t = text(n).trim();
        return t.length() > 0 ? t : desc(n).trim();
    }

    static Rect rect(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        if (n == null) return r;
        n.getBoundsInScreen(r);
        return r;
    }

    static int area(Rect r) {
        return (r.width() <= 0 || r.height() <= 0) ? 0 : r.width() * r.height();
    }

    static float ratio(Rect r, int sw, int sh) {
        if (sw <= 0 || sh <= 0) return 0f;
        return (float) area(r) / (float) (sw * sh);
    }

    static boolean clickable(AccessibilityNodeInfo n) {
        if (n.isClickable()) return true;
        java.util.List<AccessibilityNodeInfo.AccessibilityAction> list = n.getActionList();
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) {
            AccessibilityNodeInfo.AccessibilityAction a = list.get(i);
            if (a != null && a.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        }
        return false;
    }

    static boolean contains(Rect outer, int x, int y) {
        return x >= outer.left && x <= outer.right && y >= outer.top && y <= outer.bottom;
    }

    static int dp(float dp, float density) {
        return (int) (dp * density + 0.5f);
    }

    /** id 按 _ 或 - 切词，便于做"包含某词"的判定 */
    static String[] tokens(String idTail) {
        if (idTail == null || idTail.length() == 0) return new String[0];
        return idTail.toLowerCase().split("[_\\-.]");
    }
}
