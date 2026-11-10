package com.luoqiang.bilipause;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 各品牌「无障碍 / 自启动 / 电池白名单」设置页的跳转入口。
 *
 * 原则：
 * 1) 只用桌面入口，不申请任何权限（没有 QUERY_ALL_PACKAGES 也能用显式 ComponentName 拉起）。
 * 2) 每个品牌给多个候选组件，挨个试，能打开就停；全打不开就退回"应用详情页"+文字路径。
 * 3) 厂商改版很随意，所以永远保留手动文字路径兜底，不指望一定能一键跳到位。
 */
final class VendorGuide {

    static final class Brand {
        final String name;
        final String rom;
        final String steps;
        final String[][] startup;
        final String[][] power;

        Brand(String name, String rom, String steps, String[][] startup, String[][] power) {
            this.name = name;
            this.rom = rom;
            this.steps = steps;
            this.startup = startup;
            this.power = power;
        }
    }

    private static final String[][] NONE = new String[0][];

    private VendorGuide() {
    }

    static List<Brand> brands() {
        List<Brand> list = new ArrayList<Brand>();

        list.add(new Brand("通用 · 所有机型", "Android 原生 / 未列出的品牌",
                "1. 设置 → 无障碍 → 已下载的服务 → 跳过B站暂停广告 → 打开\n"
                        + "2. 设置 → 电池 → 电池优化 → 找到本应用 → 不优化\n"
                        + "3. 应用信息页里把「后台运行」相关限制改为允许",
                NONE, NONE));

        list.add(new Brand("小米 / Redmi / POCO", "MIUI · HyperOS",
                "1. 设置 → 应用设置 → 应用管理 → 跳过B站暂停广告 → 自启动 打开\n"
                        + "2. 同上页面 → 省电策略 → 无限制\n"
                        + "3. 同上页面 → 权限 → 显示在其他应用上层（可选）\n"
                        + "4. 最近任务里下拉本应用卡片，加锁防止被一键清理\n"
                        + "5. 设置 → 无障碍 → 已下载的服务 → 打开本应用",
                new String[][]{
                        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}
                },
                new String[][]{
                        {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
                        {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"}
                }));

        list.add(new Brand("华为", "EMUI · HarmonyOS",
                "1. 设置 → 应用和服务 → 应用启动管理 → 找到本应用 → 改为「手动管理」\n"
                        + "   并把 自启动 / 关联启动 / 后台活动 三项都打开\n"
                        + "2. 设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接（可开）\n"
                        + "3. 手机管家 → 应用启动管理 → 同上\n"
                        + "4. 最近任务里下拉本应用卡片加锁",
                new String[][]{
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}
                },
                new String[][]{
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"}
                }));

        list.add(new Brand("荣耀", "MagicOS",
                "1. 设置 → 应用 → 应用启动管理 → 本应用 → 手动管理（三项全开）\n"
                        + "2. 设置 → 电池 → 更多电池设置\n"
                        + "3. 系统管家 → 应用启动管理\n"
                        + "4. 最近任务里下拉卡片加锁",
                new String[][]{
                        {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}
                },
                new String[][]{
                        {"com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"}
                }));

        list.add(new Brand("OPPO / 一加 / realme", "ColorOS",
                "1. 设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 / 允许自启动\n"
                        + "2. 设置 → 应用管理 → 本应用 → 耗电管理 → 允许后台运行\n"
                        + "3. 手机管家 → 权限隐私 → 自启动管理 → 打开本应用\n"
                        + "4. 最近任务里下拉卡片加锁",
                new String[][]{
                        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                        {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"}
                },
                new String[][]{
                        {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
                        {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"}
                }));

        list.add(new Brand("vivo / iQOO", "OriginOS · Funtouch",
                "1. 设置 → 电池 → 后台耗电管理 → 找到本应用 → 允许后台高耗电（点下面③直达）\n"
                        + "2. i管家 → 应用管理 → 自启动（本应用没有开机广播，列表里可能没有，属正常，可跳过）\n"
                        + "3. 设置 → 应用与权限 → 权限管理 → 自启动\n"
                        + "4. 最近任务里下拉卡片加锁",
                new String[][]{
                        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"}
                },
                // vivo 没有稳定的「后台高耗电」直达页，交给通用的电池优化列表 + 文字路径
                NONE));

        list.add(new Brand("三星", "One UI",
                "1. 设置 → 电池和设备维护 → 电池 → 后台使用限制 → 从「使休眠的应用」里移除本应用\n"
                        + "2. 设置 → 应用 → 本应用 → 电池 → 不受限制\n"
                        + "3. 设备维护 → 电池 → 应用电源管理",
                NONE,
                new String[][]{
                        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                        {"com.samsung.android.lool", "com.samsung.android.sm_cn.ui.battery.BatteryActivity"}
                }));

        list.add(new Brand("魅族", "Flyme",
                "1. 手机管家 → 权限管理 → 后台管理 → 本应用 → 允许后台运行\n"
                        + "2. 手机管家 → 自启动管理 → 打开本应用\n"
                        + "3. 设置 → 电量管理 → 耗电详情 → 保持后台运行",
                new String[][]{
                        {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"}
                },
                NONE));

        list.add(new Brand("中兴 / 努比亚 / 其他", "MyOS · 其它定制系统",
                "1. 设置里搜索「自启动」「后台运行」「电池优化」，把本应用加入白名单\n"
                        + "2. 手机管家里找「自启动管理」「后台应用管理」\n"
                        + "3. 最近任务里下拉卡片加锁\n"
                        + "4. 实在找不到，就用下面的「应用详情页」逐项检查权限与电池设置",
                NONE, NONE));

        return list;
    }

    // ---------- 跳转 ----------

    /** 依次尝试候选组件；成功返回 null，全失败返回最后一个异常信息 */
    static String openComponents(Context c, String[][] candidates) {
        if (candidates == null || candidates.length == 0) return "该系统没有这个入口";
        String last = "该系统没有这个入口";
        for (int i = 0; i < candidates.length; i++) {
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(candidates[i][0], candidates[i][1]));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
                return null;
            } catch (Exception e) {
                last = e.getClass().getSimpleName();
            }
        }
        return last;
    }

    static String openAccessibility(Context c) {
        try {
            Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static String openBatteryOptimization(Context c) {
        try {
            Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static String openAppDetail(Context c) {
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(Uri.parse("package:" + c.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(it);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }
}
