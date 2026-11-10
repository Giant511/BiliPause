package com.luoqiang.bilipause;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** 规则调度：扫一次 → 判定 → 点击 → 复查重试 → 无效则留证据并冷却。 */
final class RuleEngine {

    private static final long RETRY_GAP = 700L;
    private static final long GIVE_UP_COOLDOWN = 10000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long DUMP_GAP = 5000L;

    private final AdGuardService svc;
    private long nextActionAt;
    private int attempts;
    private boolean wasShowing;
    private long lastDumpAt;
    private long lastCaptureAt;
    private boolean followUp;

    RuleEngine(AdGuardService svc) {
        this.svc = svc;
    }

    void reset() {
        nextActionAt = 0L;
        attempts = 0;
        wasShowing = false;
        followUp = false;
    }

    boolean followUp() {
        return followUp;
    }

    void tick() {
        followUp = false;
        long now = SystemClock.uptimeMillis();

        if (!Prefs.enabled()) {
            ServiceState.setPhase("总开关已关闭");
            return;
        }
        if (now < nextActionAt) return;

        AccessibilityNodeInfo root;
        try {
            root = svc.getRootInActiveWindow();
        } catch (Exception e) {
            root = null;
        }
        if (root == null) {
            ServiceState.setPhase("拿不到窗口节点（B 站可能刚切换页面）");
            return;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !AdGuardService.BILI.contentEquals(pkg)) {
            ServiceState.setPhase("当前前台不是 B 站，跳过");
            return;
        }

        // 一手：广告前的提示条「1秒后将展示广告　取消」。点取消，广告根本不出现。
        try {
            PauseAdRule.Scan pre = PauseAdRule.scanPreCancel(svc, root);
            if (pre != null && pre.close != null) {
                boolean ok = Clicker.click(svc, pre.close, svc.screenWidth(), svc.screenHeight());
                svc.gate().noteClick(now);
                nextActionAt = now + 900L;
                followUp = true;
                LogRing.add(ok ? "已点「取消」，广告未展示" : "点「取消」未生效，等兜底规则");
                ServiceState.setPhase(ok ? "已在展示前拦截广告" : "预拦截未生效");
                if (ok) Prefs.incHits();
                return;
            }
        } catch (Exception e) {
            LogRing.add("预拦截异常: " + e);
        }

        PauseAdRule.Scan s;
        try {
            s = PauseAdRule.scan(svc, root);
        } catch (Exception e) {
            LogRing.add("规则扫描异常: " + e);
            return;
        }

        // 抓取模式：不管有没有命中，每 2s 存一份现场，用于版本升级后离线修规则
        if (Prefs.capture() && now - lastCaptureAt > 2000L) {
            lastCaptureAt = now;
            String dump = NodeWalker.dump(root, "抓取 " + (s == null ? "无命中" : "命中 score=" + s.score));
            Evidence.save(svc, dump);
            ServiceState.setDump(dump);
            if (Prefs.screenshot()) ScreenProbe.capture(svc, "cap");
        }

        if (s == null) {
            if (wasShowing) {
                LogRing.add("广告层已消失，暂停画面干净");
                wasShowing = false;
            }
            attempts = 0;
            ServiceState.setPhase("监听中，未发现暂停广告");
            return;
        }

        wasShowing = true;
        followUp = true;

        // 命中即留证据，方便版本升级后离线修规则
        if (now - lastDumpAt > DUMP_GAP) {
            lastDumpAt = now;
            ServiceState.setDump(NodeWalker.dump(root, "暂停广告命中 score=" + s.score));
            if (Prefs.screenshot()) {
                ScreenProbe.capture(svc, "hit");
            }
        }

        int sw = svc.screenWidth();
        int sh = svc.screenHeight();

        if (attempts >= MAX_ATTEMPTS) {
            LogRing.add("连续 " + MAX_ATTEMPTS + " 次点击无效，冷却 10s（节点树已留存，可在主页复制）");
            ServiceState.setPhase("点击无效，冷却中");
            attempts = 0;
            nextActionAt = now + GIVE_UP_COOLDOWN;
            return;
        }

        boolean ok = Clicker.click(svc, s.close, sw, sh);
        attempts++;
        nextActionAt = now + RETRY_GAP;
        svc.gate().noteClick(now);

        LogRing.add((ok ? "点击关闭成功" : "点击未生效")
                + " score=" + s.score + " [" + s.why + "] 第" + attempts + "次");
        ServiceState.setPhase(ok ? "发现暂停广告，已点关闭" : "发现暂停广告，点击未生效");
        if (ok) Prefs.incHits();
    }
}
