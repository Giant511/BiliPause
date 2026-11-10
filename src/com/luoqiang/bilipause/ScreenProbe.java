package com.luoqiang.bilipause;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * 用无障碍的 takeScreenshot 存一张当前画面，仅用于排查（默认关闭）。
 *
 * 注意：HardwareBuffer / ScreenshotResult 都是 API 26/30 才有的类，
 * 因此真正干活的代码全部关在 Shot 这个嵌套类里。Android 的类加载是懒的，
 * API 24/25 的机器只会加载 ScreenProbe 本身，不会碰到 Shot，避免 VerifyError。
 */
final class ScreenProbe {

    interface Cb {
        void onDone(String msg);
    }

    private ScreenProbe() {
    }

    static void capture(AccessibilityService svc, String tag) {
        capture(svc, tag, null);
    }

    static void capture(AccessibilityService svc, String tag, Cb cb) {
        if (Build.VERSION.SDK_INT < 30) {
            done(cb, "截图需要 Android 11 及以上");
            return;
        }
        Shot.run(svc, tag, cb);
    }

    static String saveFile(AccessibilityService svc, Bitmap soft, String tag) {
        try {
            File dir = svc.getExternalFilesDir(null);
            if (dir == null) dir = svc.getFilesDir();
            if (dir == null) return "截图失败：无可用目录";
            if (!dir.exists() && !dir.mkdirs()) return "截图失败：无法创建目录";
            String name = (tag == null ? "shot" : tag) + "-"
                    + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + ".png";
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            soft.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            prunePng(dir);
            return "截图已保存：" + f.getAbsolutePath();
        } catch (Exception e) {
            return "截图保存失败：" + e;
        }
    }

    /** 只保留最近 8 张截图，避免抓取模式把存储写满 */
    private static void prunePng(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null) return;
            java.util.ArrayList<File> pngs = new java.util.ArrayList<File>();
            for (int i = 0; i < all.length; i++) {
                if (all[i].getName().endsWith(".png")) pngs.add(all[i]);
            }
            if (pngs.size() <= 8) return;
            java.util.Collections.sort(pngs, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (int i = 0; i < pngs.size() - 8; i++) pngs.get(i).delete();
        } catch (Exception ignore) {
        }
    }

    static void done(final Cb cb, final String msg) {
        LogRing.add(msg);
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                cb.onDone(msg);
            }
        });
    }

    /** 只有 API 30+ 才会被加载 */
    private static final class Shot {

        private static final Executor DIRECT = new Executor() {
            @Override
            public void execute(Runnable r) {
                r.run();
            }
        };

        static void run(final AccessibilityService svc, final String tag, final Cb cb) {
            try {
                svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, DIRECT,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                String msg = persist(result, svc, tag);
                                done(cb, msg);
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                done(cb, "截图失败 errorCode=" + errorCode);
                            }
                        });
            } catch (Exception e) {
                done(cb, "截图异常：" + e);
            }
        }

        private static String persist(AccessibilityService.ScreenshotResult result,
                                      AccessibilityService svc, String tag) {
            android.hardware.HardwareBuffer hb = null;
            try {
                hb = result.getHardwareBuffer();
                Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                if (bmp == null) return "截图失败：无法包装缓冲区";
                Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                if (soft == null) return "截图失败：无法转成软件位图";
                return saveFile(svc, soft, tag);
            } catch (Exception e) {
                return "截图保存失败：" + e;
            } finally {
                if (hb != null) {
                    try {
                        hb.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }
}
