package com.luoqiang.bilipause;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 200 条环形日志，供界面直接展示。 */
final class LogRing {

    private static final int CAP = 200;
    private static final String[] LINES = new String[CAP];
    private static int count;
    private static int next;
    private static Runnable listener;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private LogRing() {
    }

    static synchronized void add(String msg) {
        LINES[next] = FMT.format(new Date()) + " " + msg;
        next = (next + 1) % CAP;
        if (count < CAP) count++;
        // 同时进 logcat，方便 adb logcat -s BiliPause 直接看
        android.util.Log.i("BiliPause", msg);
        Runnable r = listener;
        if (r != null) r.run();
    }

    static synchronized void clear() {
        count = 0;
        next = 0;
        for (int i = 0; i < CAP; i++) LINES[i] = null;
        Runnable r = listener;
        if (r != null) r.run();
    }

    static synchronized String dump(int limit) {
        if (count == 0) return "（暂无日志）";
        int n = Math.min(limit, count);
        StringBuilder sb = new StringBuilder(n * 48);
        int start = (next - n + CAP) % CAP;
        for (int i = 0; i < n; i++) {
            String s = LINES[(start + i) % CAP];
            if (s != null) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    static synchronized void setListener(Runnable r) {
        listener = r;
    }
}
