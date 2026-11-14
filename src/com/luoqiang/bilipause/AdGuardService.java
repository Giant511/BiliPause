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
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 唯一的核心服务：只在 B 站前台、只在播放器页、只在有广告层时才动。
 * 扫描全部在独立线程，主线程零负担。
 */
public class AdGuardService extends AccessibilityService {

    static final String BILI = "tv.danmaku.bili";

    /**
     * 是不是 B 站（含应用分身/双开）。
     * 分身常见两种：同包名不同 userId（这里不用管，包名一样就认），
     * 以及厂商克隆包名（com.vivo.app.clone.tv.danmaku.bili 这类，包名以原包名结尾）。
     */
    static boolean isBili(CharSequence pkg) {
        if (pkg == null) return false;
        String s = pkg.toString();
        if (s.length() == 0) return false;
        if (BILI.equals(s)) return true;
        if (s.startsWith(BILI + ":")) return true;
        return s.endsWith("." + BILI);
    }
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
    /** 密集采样截止时间：识别到暂停后的一段时间内高频扫描，抓只存在 3 秒的广告提示条 */
    private volatile long burstUntil;

    void burst(long ms) {
        long t = SystemClock.uptimeMillis() + ms;
        if (t > burstUntil) burstUntil = t;
    }
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
        // 服务被系统重绑时实例是新的，playerSeen 会被重置成 false，
        // 而暂停状态下不会再有"进入播放器页"事件把它拉回来 —— 采样就此断掉。
        // 所以从进程内保存的当前页面恢复。
        playerSeen = isPlayer(ServiceState.activity);
        registerScreenReceiver();

        // 幂等：服务重连时 onServiceConnected 会再进来一次，
        // 不先摘掉旧的就会有两路 ticker 同时跑，扫描节奏翻倍、日志交替。
        main.removeCallbacks(captureTicker);
        main.postDelayed(captureTicker, 1000L);
        LogRing.add("无障碍服务已连接");
        ServiceState.setPhase("已连接，等待进入 B 站播放器");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence p = e.getPackageName();
        if (!isBili(p)) return;

        long now = SystemClock.uptimeMillis();
        gate.noteEvent(now);

        // 提示条也可能是浮层/Toast 形态：它的文字不会出现在普通窗口树里，
        // 但会随事件（TYPE_NOTIFICATION_STATE_CHANGED）带出来。看到就进密集采样。
        CharSequence et = eventText(e);
        if (et != null && et.length() > 0
                && (et.toString().contains("后将展示广告") || et.toString().contains("后将展示推广"))) {
            // 事件里的文案比取树早到，看到就先开密集窗口再扫一次，别等下一拍
            LogRing.add("提示条文案事件: " + et);
            burst(12000L);
            gate.reset();
            scheduleScan();
        }

        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence c = e.getClassName();
            if (c != null && !c.toString().startsWith("android.") && !isDialogCls(c.toString())) {
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
        // 还没确认在播放器页时，用更慢的节奏扫（1500ms），只用于"重新识别当前页面"。
        // 真正的出手判断在 RuleEngine 里按控件 id 复核，首页/动态页不会被误点。
        if (!gate.allowScan(now, playerSeen)) return;
        scheduleScan();
    }

    private long hbAt;
    private long hbLast;
    private long hbMaxGap;
    private int hbTicks;
    private int hbSkip;

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

    /**
     * 主动采样器。
     * - 暂停后的 8 秒内 200ms 一拍：广告提示条只存在 3 秒，靠事件驱动来不及
     * - 抓取模式 1 秒一拍：留完整现场
     * - 平时 2 秒一拍：几乎不耗电
     */
    private final Runnable captureTicker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            boolean bursting = now < burstUntil;
            gate.setDense(bursting);
            try {
                if (screenOn) {
                    // playerSeen 未知时也要定期扫一次，否则页面静止（暂停且无弹幕）时
                    // 永远等不到事件，状态会一直停在"不在播放器页"——整应用失效。
                    // 播放器页常驻 600ms 采样：暂停后控制条会自己隐藏，
                    // 靠 gemini_halfscreen_play 判定暂停经常判不到，200ms 的爆发窗口就永远不触发。
                    // 提示条只存在 3 秒，600ms 才有 5 次机会，这是能抓住它的最低频率。
                    if (bursting || Prefs.capture() || playerSeen || !playerSeen) {
                        if (gate.isBusy()) hbSkip++;
                        else scheduleScan();
                    }
                    hbTicks++;
                }
                if (Prefs.capture() && now - hbAt > 5000L) {
                    LogRing.add("心跳 ticks=" + hbTicks + " 跳过=" + hbSkip
                            + " 最大间隔=" + hbMaxGap + "ms 爆发=" + (bursting ? 1 : 0)
                            + " 绑定=" + (playerSeen ? 1 : 0));
                    hbTicks = 0;
                    hbSkip = 0;
                    hbMaxGap = 0;
                    hbAt = now;
                }
            } catch (Throwable ignore) {
            }
            long gap = bursting ? 200L
                    : (Prefs.capture() ? 1000L : (playerSeen ? 600L : 2000L));
            if (bursting) {
                long delta = now - hbLast;
                if (hbLast > 0 && delta > hbMaxGap) hbMaxGap = delta;
                hbLast = now;
            }
            main.postDelayed(this, gap);
        }
    };

    @Override
    public void onInterrupt() {
        LogRing.add("服务被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            main.removeCallbacks(captureTicker);
        } catch (Exception ignore) {
        }
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

    /** 由控件树确认"当前确实在播放器页"，把状态补回来 */
    void markPlayerSeen() {
        if (playerSeen) return;
        playerSeen = true;
        gate.reset();
        LogRing.add("进入播放器页: " + (topActivity.isEmpty() ? "（由控件识别）" : shortCls(topActivity) + " · 控件识别"));
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

    /**
     * 所有属于 B 站的窗口根节点（活动窗口 + 其它可交互窗口）。
     * B 站的暂停广告提示条可能挂在独立窗口里，只读 getRootInActiveWindow() 会漏掉。
     */
    List<AccessibilityNodeInfo> allRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        Set<Integer> seen = new HashSet<Integer>();
        try {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a != null) {
                out.add(a);
                AccessibilityWindowInfo w = a.getWindow();
                if (w != null) seen.add(w.getId());
            }
        } catch (Exception ignore) {
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (int i = 0; i < ws.size(); i++) {
                    AccessibilityWindowInfo w = ws.get(i);
                    if (w == null || seen.contains(w.getId())) continue;
                    AccessibilityNodeInfo r;
                    try {
                        r = w.getRoot();
                    } catch (Exception e) {
                        r = null;
                    }
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (isBili(p)) {
                        out.add(r);
                        seen.add(w.getId());
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 当前所有可交互窗口的概览，用于判断提示条是不是独立窗口 */
    String winSummary() {
        StringBuilder sb = new StringBuilder(96);
        int activeId = -1;
        try {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            AccessibilityWindowInfo aw = a == null ? null : a.getWindow();
            if (aw != null) activeId = aw.getId();
        } catch (Exception ignore) {
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return "none";
            for (int i = 0; i < ws.size() && i < 5; i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w == null) continue;
                sb.append(w.getType()).append('/');
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                } catch (Exception ignore) {
                }
                CharSequence pn = r == null ? null : r.getPackageName();
                sb.append(pn == null ? "?" : shortPkg(pn.toString()));
                if (w.getId() == activeId) {
                    // 只有 active 窗口记 bounds，用来判断 B 站是不是被放进了小窗
                    Rect b = NodeUtil.rect(r == null ? null : r);
                    sb.append('*').append(b == null ? "?" : b.toShortString());
                }
                sb.append(' ');
            }
        } catch (Exception e) {
            return "err";
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private static String shortPkg(String p) {
        if (p.startsWith("tv.danmaku.bili")) return "bili";
        if (p.startsWith("com.android.systemui")) return "sysui";
        if (p.startsWith("com.android")) return "android";
        int i = p.lastIndexOf('.');
        return i > 0 ? p.substring(i + 1) : p;
    }

    /**
     * 抓取模式专用：把**非 B 站窗口**的文本也记下来。
     * 暂停广告的提示条有可能挂在独立窗口（系统浮层 / 另一个 Activity 窗口）里，
     * 只 dump 活动窗口永远看不到它，也就没法判断"到底是树里没有，还是我们找错了地方"。
     */
    String dumpOtherWindows() {
        StringBuilder sb = new StringBuilder(1024);
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return "";
            for (int i = 0; i < ws.size() && i < 6; i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w == null) continue;
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                } catch (Exception ignore) {
                }
                if (r == null) continue;
                CharSequence pn = r.getPackageName();
                if (isBili(pn)) continue;
                sb.append("--- win").append(i).append(" type=").append(w.getType())
                        .append(" pkg=").append(pn).append(" bounds=")
                        .append(NodeUtil.rect(r).toShortString()).append('\n');
                collectText(r, sb, 0);
            }
        } catch (Exception ignore) {
        }
        return sb.toString();
    }

    private void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 8 || sb.length() > 3000) return;
        String t = NodeUtil.text(n).trim();
        String d = NodeUtil.desc(n).trim();
        String put = t.length() > 0 ? t : d;
        if (put.length() > 0 && put.length() < 60) sb.append("  ").append(put).append('\n');
        int c;
        try {
            c = n.getChildCount();
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < c && i < 40; i++) {
            try {
                collectText(n.getChild(i), sb, depth + 1);
            } catch (Exception e) {
                break;
            }
        }
    }

    AccessibilityNodeInfo activeRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    /** 弹窗/浮层（B 站的 BottomDialogMenu 等）不算"离开播放器页"，否则它一出现采样就停 */
    private static boolean isDialogCls(String cls) {
        String s = cls.toLowerCase();
        return s.contains("dialog") || s.contains("popup") || s.contains("menu")
                || s.contains("sheet") || s.contains("panel");
    }

    static boolean isPlayer(String cls) {
        if (cls == null || cls.length() == 0) return false;
        String s = cls.toLowerCase();
        return s.contains("videodetails")
                || s.contains("unitedbizdetails")
                || s.contains("theseus")
                || s.contains("player");
    }

    private static CharSequence eventText(AccessibilityEvent e) {
        try {
            List<CharSequence> ts = e.getText();
            if (ts == null || ts.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ts.size(); i++) {
                if (ts.get(i) != null) sb.append(ts.get(i)).append(' ');
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            return null;
        }
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
