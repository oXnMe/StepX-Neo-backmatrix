package com.stepx.backmatrix.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行放行。
 *
 * Android 唯一的正规入口是电池优化白名单（有标准 Intent，可直接弹系统确认框）；
 * 国产 ROM 的「自启动 / 后台管理」既没有 API 也不给读取，只能按包名跳设置页。
 * 这些开关的状态读不到，界面上不要假装显示。
 */
object PowerGuide {

    /** 有没有进电池优化白名单。这是唯一能可靠读到的状态。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)

    /** 弹「忽略电池优化」的系统确认框；ROM 不认这个 Intent 时退到电池优化列表。 */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(ctx: Context): Boolean {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(ask) }.isSuccess) return true

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(list) }.isSuccess
    }

    /**
     * 各家「自启动 / 后台管理」页面。同一个包里不同 ROM 版本会换 Activity 名，
     * 所以一个厂商列了不止一条，按顺序试。
     */
    private val AUTO_START_PAGES = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
    )

    /** 跳到厂商的自启动管理页；全都跳不过去就退到应用详情页。 */
    fun openAutoStartSettings(ctx: Context): Boolean {
        for ((pkg, cls) in AUTO_START_PAGES) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
        }
        return openAppDetails(ctx)
    }

    /** 应用详情页：国产 ROM 的「耗电和后台」「手动管理」都从这里进。 */
    fun openAppDetails(ctx: Context): Boolean {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(details) }.isSuccess
    }
}
