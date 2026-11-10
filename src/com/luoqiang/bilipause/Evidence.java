package com.luoqiang.bilipause;

import android.accessibilityservice.AccessibilityService;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 抓取模式用的证据落地：把节点树按时间存成文件，只保留最近若干份。
 * 存在 /sdcard/Android/data/com.luoqiang.bilipause/files/capture/，adb pull 可直接取。
 */
final class Evidence {

    private static final int KEEP = 12;
    private static final SimpleDateFormat TS = new SimpleDateFormat("MMdd-HHmmss", Locale.US);

    private Evidence() {
    }

    static File captureDir(AccessibilityService svc) {
        File dir = svc.getExternalFilesDir(null);
        if (dir == null) dir = svc.getFilesDir();
        if (dir == null) return null;
        File sub = new File(dir, "capture");
        if (!sub.exists() && !sub.mkdirs()) return null;
        return sub;
    }

    static String save(AccessibilityService svc, String text) {
        File dir = captureDir(svc);
        if (dir == null) return "无可用目录";
        try {
            File f = new File(dir, "cap-" + TS.format(new Date()) + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
            prune(dir);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "保存失败：" + e;
        }
    }

    private static void prune(File dir) {
        File[] all = dir.listFiles();
        if (all == null || all.length <= KEEP) return;
        Arrays.sort(all, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        int drop = all.length - KEEP;
        for (int i = 0; i < drop; i++) {
            if (!all[i].delete()) break;
        }
    }
}
