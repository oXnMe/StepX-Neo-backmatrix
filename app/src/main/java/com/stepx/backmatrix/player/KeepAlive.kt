package com.stepx.backmatrix.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 通知权限与无障碍保活的状态查询 / 跳转。
 *
 * 两个状态都读得到：通知走 `checkSelfPermission`，无障碍读
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里有没有本应用的组件。
 * 读不到的是国产 ROM 的自启动开关，那部分交给 [com.stepx.backmatrix.ui.PowerGuide]。
 */
object KeepAlive {

    /** 通知权限。Android 13 以下装了就有，不用问。 */
    fun isNotificationGranted(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT < 33) true
        else ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 无障碍保活是否已开启。
     *
     * 读的是系统那张「已启用的无障碍服务」列表，形如 `pkg/cls:pkg2/cls2`。
     * 不能只看进程内 [KeepAliveService.alive]：从系统设置回来时进程可能刚重启。
     */
    fun isAccessibilityEnabled(ctx: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val target = ComponentName(ctx, KeepAliveService::class.java).flattenToString()
        enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }.getOrDefault(false)

    /** 跳系统的无障碍列表页，用户在那一页自己勾选本应用。 */
    fun openAccessibilitySettings(ctx: Context): Boolean {
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct) }.isSuccess) return true

        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(fallback) }.isSuccess
    }
}
