package com.stepx.backmatrix.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stepx.backmatrix.R
import com.stepx.backmatrix.ui.MainActivity

/**
 * 桥的前台服务：只负责「进程活着」和「通知栏有个凭证」。
 *
 * 服务本身在 [BridgeHub] 里起；即使前台服务被系统拦下，用户点了启动之后桥依然可用。
 * 用 specialUse 而不是 dataSync：dataSync 在 Android 15 上有每天 6 小时的时限，
 * 到点会被系统掐掉，而这块桥要一直等着 H5 来调。
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.stepx.backmatrix.BRIDGE_START"
        const val ACTION_STOP = "com.stepx.backmatrix.BRIDGE_STOP"
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 51

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java).apply { action = ACTION_START }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, BridgeService::class.java).apply { action = ACTION_STOP })
            }
        }

        /** 用户的选择存盘，应用重开时照着恢复。 */
        fun remember(context: Context, on: Boolean) {
            context.getSharedPreferences(BridgeConfig.PREF_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(BridgeConfig.KEY_ON, on).apply()
        }

        fun isRememberedOn(context: Context): Boolean =
            context.getSharedPreferences(BridgeConfig.PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(BridgeConfig.KEY_ON, false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 被系统拉回来时 intent 是 null，这里必须先转前台再分支，
        // 否则会走到「startForegroundService 没调 startForeground」的崩溃
        startForeground(NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_STOP) {
            stopBridge()
            return START_NOT_STICKY
        }

        if (!BridgeHub.start(this)) {
            // 端口被占之类：别留一条「运行中」的通知骗人
            remember(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        remember(this, true)
        BridgeWatchdog.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeHub.stop()
        super.onDestroy()
    }

    private fun stopBridge() {
        BridgeHub.stop()
        BridgeWatchdog.cancel(this)
        remember(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "背屏桥", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "本地 HTTP 桥开着时显示"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, BridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_matrix)
            .setContentTitle("背屏桥运行中")
            .setContentText("${BridgeConfig.baseUrl} · 等待 Studio 调用")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }
}
