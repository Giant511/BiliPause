package com.luoqiang.bilipause;

/**
 * 事件节流闸门。
 * 弹幕刷屏时 B 站播放器每秒能发上百个 TYPE_WINDOW_CONTENT_CHANGED，
 * 这里是"轻量"的第一道闸：不满足间隔一律直接丢掉，连节点树都不取。
 */
final class EventGate {

    /** 正常节奏：300ms 扫一次 */
    private static final long GAP_NORMAL = 300L;
    /** 事件风暴（弹幕密集）：降到 800ms */
    private static final long GAP_STORM = 800L;
    /** 识别不出的播放器页：1500ms 慢慢扫，别浪费电 */
    private static final long GAP_UNKNOWN = 1500L;
    /** 点完一次至少等 900ms 再动，避免连点把广告页点开 */
    private static final long GAP_AFTER_CLICK = 900L;
    private static final int STORM_THRESHOLD = 200;

    private long lastScanAt = -10000L;
    private long lastClickAt = -10000L;
    private long bucketStart;
    private int bucketCount;
    private boolean busy;

    void noteEvent(long now) {
        if (now - bucketStart > 1000L) {
            bucketStart = now;
            bucketCount = 0;
        }
        bucketCount++;
    }

    boolean stormy() {
        return bucketCount > STORM_THRESHOLD;
    }

    boolean isBusy() {
        return busy;
    }

    void setBusy(boolean b) {
        busy = b;
    }

    boolean allowScan(long now, boolean knownPlayer) {
        if (busy) return false;
        long gap = stormy() ? GAP_STORM : (knownPlayer ? GAP_NORMAL : GAP_UNKNOWN);
        if (now - lastScanAt < gap) return false;
        if (now - lastClickAt < GAP_AFTER_CLICK) return false;
        lastScanAt = now;
        return true;
    }

    void noteClick(long now) {
        lastClickAt = now;
    }

    void reset() {
        lastScanAt = -10000L;
        lastClickAt = -10000L;
        busy = false;
    }
}
