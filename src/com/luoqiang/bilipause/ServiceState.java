package com.luoqiang.bilipause;

/** 进程内共享状态：服务是否连接、当前阶段、最近一次节点树快照。不做 AIDL。 */
final class ServiceState {

    static volatile boolean connected;
    static volatile String phase = "未启动";
    static volatile String activity = "";
    static volatile String lastDump = "";
    static volatile long lastDumpAt;

    private ServiceState() {
    }

    static void setPhase(String p) {
        phase = p;
    }

    static void setDump(String s) {
        lastDump = s == null ? "" : s;
        lastDumpAt = System.currentTimeMillis();
    }
}
