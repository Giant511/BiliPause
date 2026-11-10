package com.luoqiang.bilipause;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** 三级点击：节点 ACTION_CLICK → 向上找同样小的可点祖先 → 坐标手势。 */
final class Clicker {

    private Clicker() {
    }

    static boolean click(AccessibilityService svc, AccessibilityNodeInfo n, int sw, int sh) {
        if (n == null) return false;
        if (tryClick(n)) return true;

        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo parent;
            try {
                parent = p.getParent();
            } catch (Exception e) {
                parent = null;
            }
            if (parent == null) break;
            Rect r = NodeUtil.rect(parent);
            // 只肯爬到尺寸同样小的父容器；一旦父容器是整层广告就停手，避免把广告页点开
            if (NodeUtil.ratio(r, sw, sh) > 0.05f) break;
            if (tryClick(parent)) return true;
            p = parent;
        }

        Rect r = NodeUtil.rect(n);
        if (NodeUtil.area(r) <= 0) return false;
        return tap(svc, r.centerX(), r.centerY());
    }

    private static boolean tryClick(AccessibilityNodeInfo n) {
        try {
            return n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean tap(AccessibilityService svc, int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 40);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            return svc.dispatchGesture(gd, null, null);
        } catch (Exception e) {
            LogRing.add("手势点击失败: " + e);
            return false;
        }
    }
}
