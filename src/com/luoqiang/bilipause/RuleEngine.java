package com.luoqiang.bilipause;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** 规则调度：扫一次 → 判定 → 点击 → 复查重试 → 无效则留证据并冷却。 */
final class RuleEngine {

    private static final long RETRY_GAP = 500L;
    /** 一轮里点不中就快速重来，别整轮放弃（暂停广告窗口只有几秒） */
    private static final long GIVE_UP_COOLDOWN = 3000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long DUMP_GAP = 5000L;

    private final AdGuardService svc;
    private long nextActionAt;
    private int attempts;
    private boolean wasShowing;
    private long lastDumpAt;
    private long lastCaptureAt;
    private int retries;
    /** 本轮（一次暂停）里是否真的见到过提示条 */
    private boolean sawHint;
    private long lastShotAt;
    private long episodeAt;
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
        if (!AdGuardService.isBili(pkg)) {
            ServiceState.setPhase("当前前台不是 B 站，跳过");
            return;
        }

        // 提示条/广告可能挂在独立窗口里，光看活动窗口会漏
        List<AccessibilityNodeInfo> roots = svc.allRoots();
        if (roots.isEmpty()) return;

        // 每拍只遍历一次树：提示条规则和广告规则共用同一份节点列表，开销减半。
        List<List<NodeWalker.Item>> trees = new ArrayList<List<NodeWalker.Item>>(roots.size());
        boolean paused = false;
        for (int wi = 0; wi < roots.size(); wi++) {
            List<NodeWalker.Item> items = NodeWalker.flatten(roots.get(wi));
            trees.add(items);
            if (!paused && PauseAdRule.looksPaused(items)) paused = true;
        }
        // 用控件 id 复核"确实是播放器页"，不依赖页面切换事件（重绑/重装后那个事件不会再来）
        boolean playerPage = svc.isPlayerSeen();
        if (!playerPage) {
            for (int wi = 0; wi < trees.size(); wi++) {
                if (PauseAdRule.looksPlayer(trees.get(wi))) {
                    playerPage = true;
                    svc.markPlayerSeen();
                    break;
                }
            }
        }
        if (!playerPage) {
            ServiceState.setPhase("非播放器页，跳过");
            return;
        }

        // 暂停是暂停广告唯一的触发时机：进入 8 秒密集采样，
        // 把「X秒后将展示广告　取消」这条只存在 3 秒的提示条抓住。
        if (paused) svc.burst(12000L);

        // 一手：广告前的提示条「X秒后将展示广告　取消」。点取消，广告根本不出现。
        try {
            PauseAdRule.Scan pre = null;
            for (int wi = 0; wi < trees.size() && pre == null; wi++) {
                pre = PauseAdRule.scanPreCancel(svc, roots.get(wi), trees.get(wi));
            }
            if (pre != null && pre.close != null) {
                sawHint = true;
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

        PauseAdRule.Scan s = null;
        try {
            for (int wi = 0; wi < trees.size(); wi++) {
                PauseAdRule.Scan one = PauseAdRule.scan(svc, roots.get(wi), trees.get(wi));
                if (one != null && (s == null || one.score > s.score)) s = one;
            }
        } catch (Exception e) {
            LogRing.add("规则扫描异常: " + e);
            return;
        }

        // 只要树里出现 close 语义控件，立刻切到 200ms 密集采样。
        // 竖屏播放器的控制条不进无障碍树，靠"检测到暂停"来加速在竖屏下永远不成立；
        // 而广告的 × 一进树就说明广告已经在了，这时候必须马上确认，晚一拍就点空。
        if (s != null || PauseAdRule.lastCandCount() > 0) svc.burst(4000L);

        // 抓取模式：不管有没有命中，每 2s 存一份现场，用于版本升级后离线修规则
        boolean worth = PauseAdRule.lastInteresting();
        if (Prefs.capture() && (worth || now - lastCaptureAt > 3000L)) {
            lastCaptureAt = now;
            String dump = NodeWalker.dump(root, "抓取 " + (s == null ? "无命中" : "命中 score=" + s.score)
                    + " [win=" + roots.size() + "][" + PauseAdRule.lastDiag() + "]"
                    + " [wins: " + svc.winSummary() + "]\n" + svc.dumpOtherWindows());
            Evidence.save(svc, dump);
            ServiceState.setDump(dump);
            // 截图接口有频率限制（errorCode=3），拍太勤会把扫描线程一起拖慢：
            // 实测日志里 5 次采样有 3~5 次被"跳过"，提示条那 3 秒直接被错过。
            // 只在真的命中广告时拍，且 3 秒内最多一张。
            if (Prefs.screenshot() && s != null && now - lastShotAt > 3000L) {
                lastShotAt = now;
                ScreenProbe.capture(svc, "cap");
            }
        }

        if (s == null) {
            if (wasShowing) {
                LogRing.add("广告层已消失，暂停画面干净");
                wasShowing = false;
                sawHint = false;
            }
            attempts = 0;
            ServiceState.setPhase("监听中，未发现暂停广告");
            // 树里已经出现 close 语义的控件，但还没凑齐"在广告卡片内"的证据：
            // 大概率是广告正在入场（视图逐帧挂上去），250ms 后补扫一次，别等下一个事件。
            if (PauseAdRule.lastCandCount() > 0 && retries < 12) {
                retries++;
                nextActionAt = now + 250L;
                followUp = true;
            } else if (PauseAdRule.lastCandCount() == 0) {
                retries = 0;
            }
            return;
        }

        if (!wasShowing) {
            episodeAt = now;
            LogRing.add(sawHint ? "本次广告出现过提示条，但没拦住（已进兜底）"
                    : "本次广告没有提示条（该形态只能点 ×，已进兜底）");
        }
        wasShowing = true;
        followUp = true;
        retries = 0;

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
