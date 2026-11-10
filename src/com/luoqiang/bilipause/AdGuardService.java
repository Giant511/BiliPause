package com.luoqiang.bilipause;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 唯一的核心服务：只在 B 站前台、只在播放器页、只在有广告层时才动。
 * 扫描全部在独立线程，主线程零负担。
 */
public class AdGuardService extends AccessibilityService {

    static final String BILI = "tv.danmaku.bili";
    private static final long FOLLOW_UP_DELAY = 450L;

    private static volatile AdGuardService instance;

    private HandlerThread thread;
    private Handler worker;
    private Handler main;
    private final EventGate gate = new EventGate();
    private RuleEngine engine;
    private BroadcastReceiver screenRx;

    private volatile String topActivity = "";
    private volatile boolean playerSeen;
    private volatile boolean screenOn = true;
    private volatile Rect lastAdRect;

    static AdGuardService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Prefs.init(this);
        ServiceState.connected = true;

        thread = new HandlerThread("bilipause-scan");
        thread.start();
        worker = new Handler(thread.getLooper());
        main = new Handler(Looper.getMainLooper());
        engine = new RuleEngine(this);
        screenOn = isScreenOn();
        registerScreenReceiver();

        LogRing.add("无障碍服务已连接");
        ServiceState.setPhase("已连接，等待进入 B 站播放器");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence p = e.getPackageName();
        if (p == null || !BILI.contentEquals(p)) return;

        long now = SystemClock.uptimeMillis();
        gate.noteEvent(now);

        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence c = e.getClassName();
            if (c != null && !c.toString().startsWith("android.")) {
                topActivity = c.toString();
                ServiceState.activity = topActivity;
                boolean was = playerSeen;
                playerSeen = isPlayer(topActivity);
                if (was != playerSeen) {
                    gate.reset();
                    if (engine != null) engine.reset();
                    LogRing.add(playerSeen ? "进入播放器页: " + shortCls(topActivity)
                            : "离开播放器页: " + shortCls(topActivity));
                }
            }
        }

        if (!screenOn) return;
        // 严格只在播放器页扫描。首页/动态页的推荐卡片同样带"广告"角标和叉，
        // 在那里动手属于越界，也容易误点"不感兴趣"。宁可漏点，不可误点。
        if (!playerSeen) return;
        if (!gate.allowScan(now, true)) return;
        scheduleScan();
    }

    private void scheduleScan() {
        if (worker == null || engine == null) return;
        if (gate.isBusy()) return;
        gate.setBusy(true);
        worker.post(scanTask);
    }

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            boolean needFollowUp = false;
            try {
                if (!screenOn) return;
                engine.tick();
                needFollowUp = engine.followUp();
            } catch (Throwable t) {
                LogRing.add("扫描异常: " + t);
            } finally {
                gate.setBusy(false);
            }
            if (needFollowUp) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scheduleScan();
                    }
                }, FOLLOW_UP_DELAY);
            }
        }
    };

    @Override
    public void onInterrupt() {
        LogRing.add("服务被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterScreenReceiver();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        worker = null;
        engine = null;
        instance = null;
        ServiceState.connected = false;
        ServiceState.setPhase("服务已断开");
        LogRing.add("无障碍服务已断开");
        return super.onUnbind(intent);
    }

    // ---- 以下为供界面/规则使用的取值方法 ----

    boolean isPlayerSeen() {
        return playerSeen;
    }

    EventGate gate() {
        return gate;
    }

    int screenWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    int screenHeight() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    float density() {
        return getResources().getDisplayMetrics().density;
    }

    void noteAdRect(Rect r) {
        lastAdRect = r == null ? null : new Rect(r);
    }

    AccessibilityNodeInfo activeRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isPlayer(String cls) {
        if (cls == null || cls.length() == 0) return false;
        String s = cls.toLowerCase();
        return s.contains("videodetails")
                || s.contains("unitedbizdetails")
                || s.contains("theseus")
                || s.contains("player");
    }

    static String shortCls(String cls) {
        if (cls == null) return "";
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private void registerScreenReceiver() {
        if (screenRx != null) return;
        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent == null ? "" : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    screenOn = false;
                    ServiceState.setPhase("屏幕熄灭，已停止扫描");
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    screenOn = true;
                    gate.reset();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenRx, f);
            }
        } catch (Exception e) {
            LogRing.add("注册屏幕广播失败: " + e);
            screenRx = null;
        }
    }

    private void unregisterScreenReceiver() {
        if (screenRx == null) return;
        try {
            unregisterReceiver(screenRx);
        } catch (Exception ignore) {
        }
        screenRx = null;
    }
}
