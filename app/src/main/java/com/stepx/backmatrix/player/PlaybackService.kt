package com.stepx.backmatrix.player

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
import com.stepx.backmatrix.core.Library
import com.stepx.backmatrix.R
import com.stepx.backmatrix.core.DotAnimation
import com.stepx.backmatrix.ui.MainActivity

/**
 * 让播放不被界面生命周期打断：锁屏、切后台都继续推帧。
 * 播放逻辑本身在 [MatrixPlayer]，这里只负责前台服务与通知。
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.stepx.backmatrix.PLAY"
        const val ACTION_START_TOOL = "com.stepx.backmatrix.PLAY_TOOL"
        const val ACTION_STOP = "com.stepx.backmatrix.STOP"
        const val EXTRA_ENTRY = "entry_id"
        const val EXTRA_TOOL = "tool_id"
        const val EXTRA_TITLE = "tool_title"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, entryId: String) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ENTRY, entryId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startTool(context: Context, toolId: String, title: String) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START_TOOL
                putExtra(EXTRA_TOOL, toolId)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val entryId = intent.getStringExtra(EXTRA_ENTRY)
                startForeground(NOTIFICATION_ID, buildNotification("正在连接背屏…"))
                if (entryId != null) {
                    val entry = Library.list(this).firstOrNull { it.id == entryId }
                    if (entry != null) {
                        val anim: DotAnimation? = runCatching { Library.load(this, entry) }.getOrNull()
                        if (anim != null) {
                            // 界面可能已经先开播了，别把它打回第一帧
                            val current = MatrixPlayer.state.value
                            if (!(current.playing && current.title == anim.name)) {
                                MatrixPlayer.start(this, anim)
                            }
                            notify(buildNotification("背屏播放中 · ${anim.name}"))
                        } else {
                            notify(buildNotification("读取动画失败"))
                        }
                    }
                }
            }
            ACTION_START_TOOL -> {
                val toolId = intent.getStringExtra(EXTRA_TOOL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "实时工具"
                startForeground(NOTIFICATION_ID, buildNotification("正在启动 $title…"))
                if (toolId != null) {
                    // 界面可能已经把它跑起来了；同一个工具就别重建实例（会清空传感器状态）
                    val current = MatrixPlayer.state.value
                    if (!(current.playing && current.toolId == toolId)) {
                        val instance = com.stepx.backmatrix.tools.Tools.create(this, toolId)
                        if (instance != null) {
                            MatrixPlayer.startTool(this, toolId, title, instance)
                            notify(buildNotification("背屏运行中 · $title"))
                        } else {
                            notify(buildNotification("未知工具：$toolId"))
                        }
                    } else {
                        notify(buildNotification("背屏运行中 · $title"))
                    }
                }
            }
            ACTION_STOP -> {
                MatrixPlayer.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 只停推帧，保留最后一帧；真正的“交还背屏”由 MatrixPlayer.stop 负责
        MatrixPlayer.pause(this)
        super.onDestroy()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "背屏播放", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "播放点阵动画时显示的状态通知"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_matrix)
            .setContentTitle("点阵背屏")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }
}
