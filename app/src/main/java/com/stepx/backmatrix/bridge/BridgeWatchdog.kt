package com.stepx.backmatrix.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 看门狗：每 60 秒把前台服务拉起来一次，进程被清掉之后桥还能自己回来。
 *
 * 从 Android 12 起后台启动前台服务受限，进过电池优化白名单的应用才放行；
 * 没放行时这次启动会被系统静默丢弃，不会崩，所以全都包在 runCatching 里。
 *
 * 只在「启动」时排程，「停止」时 [cancel] 掉——否则停了 60 秒又会自己回来。
 */
class BridgeWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (BridgeService.isRememberedOn(context)) {
            BridgeService.start(context)
            schedule(context)
        }
    }

    companion object {
        private const val INTERVAL_MS = 60_000L
        private const val REQUEST_CODE = 1001

        fun schedule(context: Context) {
            runCatching {
                val alarm = context.getSystemService(AlarmManager::class.java) ?: return
                alarm.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pending(context)
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
            }
        }

        private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BridgeWatchdog::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
