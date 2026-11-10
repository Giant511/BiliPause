package com.luoqiang.bilipause;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    private static final String FILE = "bilipause";
    private static final String K_ENABLED = "enabled";
    private static final String K_AGGRESSIVE = "aggressive";
    private static final String K_SHOT = "shot";
    private static final String K_HITS = "hits";
    private static final String K_CAPTURE = "capture";

    private static SharedPreferences sp;

    private Prefs() {
    }

    static synchronized void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    static boolean enabled() {
        return sp == null || sp.getBoolean(K_ENABLED, true);
    }

    static void setEnabled(boolean v) {
        if (sp != null) sp.edit().putBoolean(K_ENABLED, v).apply();
    }

    static boolean aggressive() {
        return sp != null && sp.getBoolean(K_AGGRESSIVE, false);
    }

    static void setAggressive(boolean v) {
        if (sp != null) sp.edit().putBoolean(K_AGGRESSIVE, v).apply();
    }

    static boolean screenshot() {
        return sp != null && sp.getBoolean(K_SHOT, false);
    }

    static void setScreenshot(boolean v) {
        if (sp != null) sp.edit().putBoolean(K_SHOT, v).apply();
    }

    static boolean capture() {
        return sp != null && sp.getBoolean(K_CAPTURE, false);
    }

    static void setCapture(boolean v) {
        if (sp != null) sp.edit().putBoolean(K_CAPTURE, v).apply();
    }

    static int hits() {
        return sp == null ? 0 : sp.getInt(K_HITS, 0);
    }

    static void incHits() {
        if (sp != null) sp.edit().putInt(K_HITS, hits() + 1).apply();
    }

    static void resetHits() {
        if (sp != null) sp.edit().putInt(K_HITS, 0).apply();
    }
}
