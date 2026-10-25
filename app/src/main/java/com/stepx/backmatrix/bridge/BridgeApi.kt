package com.stepx.backmatrix.bridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.stepx.backmatrix.core.BackScreen
import com.stepx.backmatrix.core.Dot
import com.stepx.backmatrix.player.MatrixPlayer
import com.stepx.backmatrix.player.PlaybackService
import org.json.JSONArray
import org.json.JSONObject

/** 能力调用失败。消息会原样回到 H5 的 `error` 字段，所以写成能直接看的句子。 */
class BridgeException(message: String) : Exception(message)

/**
 * 桥的能力分发。
 *
 * 只有三件在 PZ261A 上确证有效，Studio 提示词里也只写这三件：
 * 推帧、清屏、查状态。
 *
 * 另外 14 个是**待验证探针**，保留是为了以后换固件时能直接试：
 * - `wake_up_*` / `sleep_back_screen`：`display_ext` 里根本没有这两个方法，调用即报错；
 * - 一组 `stepos_back_screen_*` 设置读写：键名是按原厂命名推测的，写进去系统不采纳，
 *   返回里带 `bestEffort:true` 和 `applied`（写没写成功）——**它不代表生效**。
 *
 * 推帧走的是 [BackScreen.show] 的原始通道，不做亮度缩放、也不做旋转——
 * H5 给什么 391 个值就推什么，页面里的坐标才能和背屏上的点一一对应。
 * 代价是正在播的动画会跟它对抢，所以推帧前先把播放停掉。
 */
class BridgeApi(private val appContext: Context) {

    /** 给 `/health` 读机型与 display_ext 状态用。 */
    val context: Context get() = appContext

    fun call(name: String, args: JSONObject): JSONObject = when (name) {
        // ── 真机验证可用 ──────────────────────────────────────────
        "set_back_screen_frame" -> setFrame(args.optJSONArray("frame"))
        "clear_back_screen_frame" -> clearFrame()
        "get_back_screen_display_info" -> displayInfo()

        // ── 待验证：固件没暴露方法，调了就是错 ────────────────────
        "wake_up_back_screen" -> throw BridgeException("display_ext 没有 wakeUpBackScreen（固件未暴露）")
        "sleep_back_screen" -> throw BridgeException("display_ext 没有 sleepBackScreen（固件未暴露）")

        // ── 待验证：写 Settings 系统，系统不认 ────────────────────
        "set_back_screen_auto_rotate" ->
            bestEffort("stepos_back_screen_auto_rotate", boolValue(args, "enabled"))
        "get_back_screen_auto_rotate" -> JSONObject().put("ok", true).put("unverified", true)
            .put("enabled", getInt("stepos_back_screen_auto_rotate", 1) != 0)
        "set_back_screen_brightness" -> {
            val v = args.optInt("brightness", -1)
            if (v !in 1..255) throw BridgeException("brightness 需 1-255")
            bestEffort("stepos_back_screen_brightness", v)
        }
        "get_back_screen_brightness" -> JSONObject().put("ok", true).put("unverified", true)
            .put("brightness", getInt("stepos_back_screen_brightness", -1))
        "set_back_screen_auto_brightness" ->
            bestEffort("stepos_back_screen_auto_brightness", boolValue(args, "enabled"))
        "get_back_screen_auto_brightness" -> JSONObject().put("ok", true).put("unverified", true)
            .put("enabled", getInt("stepos_back_screen_auto_brightness", -1) == 1)
        "set_back_screen_camera_link" ->
            bestEffort("stepos_back_screen_camera_link", boolValue(args, "enabled"))
        "get_back_screen_camera_link" -> JSONObject().put("ok", true).put("unverified", true)
            .put("enabled", getInt("stepos_back_screen_camera_link", 0) != 0)
        "set_back_screen_display_mode" -> {
            val mode = args.optString("mode", "auto")
            bestEffort("stepos_back_screen_display_mode", mode)
        }
        "get_back_screen_display_mode" -> JSONObject().put("ok", true).put("unverified", true)
            .put("mode", getString("stepos_back_screen_display_mode", "unknown"))
        "set_back_screen_basic_type" -> {
            val type = args.optString("type", "agent")
            val result = bestEffort("stepos_back_screen_basic_type", type)
            // 待机文本只在 type=text 时有意义，返回值以类型那条为准
            if (type == "text") {
                bestEffort("stepos_back_screen_basic_text", args.optString("text", ""))
            }
            result
        }
        "get_back_screen_basic_type" -> JSONObject().put("ok", true).put("unverified", true)
            .put("type", getString("stepos_back_screen_basic_type", "agent"))

        else -> throw BridgeException("未知能力：$name。可用能力见设置页「背屏桥 → 提示词」")
    }

    private fun setFrame(raw: JSONArray?): JSONObject {
        val ints = toIntArray(raw) ?: throw BridgeException(FRAME_HINT)
        if (ints.size != Dot.PIXELS) throw BridgeException(FRAME_HINT)
        takeOverScreen()
        // Dot 内部按无符号字节处理（读的时候 and 0xFF），这里直接截成 ByteArray 即可
        val frame = ByteArray(Dot.PIXELS) { ints[it].toByte() }
        if (!BackScreen.show(appContext, frame)) {
            throw BridgeException(BackScreen.lastError ?: "背屏没有接受这一帧")
        }
        return JSONObject().put("ok", true).put("message", "已推送点阵帧")
    }

    private fun clearFrame(): JSONObject {
        takeOverScreen()
        // 传 null 帧等于交还背屏，恢复系统默认画面（时间 / amoo）
        if (!BackScreen.show(appContext, null)) {
            throw BridgeException(BackScreen.lastError ?: "背屏没有交还控制权")
        }
        return JSONObject().put("ok", true).put("message", "已清除，背屏恢复系统默认画面")
    }

    private fun displayInfo(): JSONObject {
        val composer = BackScreen.composer(appContext)
        return JSONObject()
            .put("ok", true)
            .put("width", Dot.W)
            .put("height", Dot.H)
            // 桥不旋转也不调亮度，H5 拿到的永远是原始 17×23
            .put("rotation", 0)
            .put("model", Build.MODEL ?: "unknown")
            .put("type", composer?.type ?: "unknown")
            .put("state", composer?.state ?: "unknown")
            .put("displayExtAvailable", BackScreen.hasService(appContext))
            .put("playing", MatrixPlayer.isPlaying())
    }

    /**
     * 播放器每帧都会重新提交，不先停掉的话桥推的帧几十毫秒后就被覆盖，
     * 表现为「接口返回成功、背屏还是动画」。
     */
    private fun takeOverScreen() {
        val state = MatrixPlayer.state.value
        if (!state.playing && !state.live) return
        runCatching {
            MatrixPlayer.stop(appContext)
            PlaybackService.stop(appContext)
        }
    }

    /**
     * 待验证探针的写入：只保证「请求发出去了」，返回里用 `applied` 说明系统有没有真的写进去。
     * 不写 `applied` 的话，调用方会以为亮度/显示模式这类设置已经生效。
     */
    private fun bestEffort(key: String, value: Int): JSONObject {
        val applied = runCatching {
            Settings.System.putInt(appContext.contentResolver, key, value)
        }.getOrDefault(false)
        return JSONObject().put("ok", true).put("bestEffort", true).put("applied", applied)
            .put("message", if (applied) "已写 $key" else "系统不接受 $key（这条是待验证探针）")
    }

    private fun bestEffort(key: String, value: String): JSONObject {
        val applied = runCatching {
            Settings.System.putString(appContext.contentResolver, key, value)
        }.getOrDefault(false)
        return JSONObject().put("ok", true).put("bestEffort", true).put("applied", applied)
            .put("message", if (applied) "已写 $key" else "系统不接受 $key（这条是待验证探针）")
    }

    private fun getInt(key: String, def: Int): Int = runCatching {
        Settings.System.getInt(appContext.contentResolver, key, def)
    }.getOrDefault(def)

    private fun getString(key: String, def: String): String = runCatching {
        Settings.System.getString(appContext.contentResolver, key)
    }.getOrDefault(def)

    private fun boolValue(args: JSONObject, key: String) = if (args.optBoolean(key)) 1 else 0

    private fun toIntArray(raw: JSONArray?): IntArray? {
        if (raw == null) return null
        return runCatching {
            IntArray(raw.length()) { Dot.clamp(raw.getInt(it)) }
        }.getOrNull()
    }

    private companion object {
        val FRAME_HINT = "frame 必须是 ${Dot.PIXELS} 个 0-255 整数"
    }
}
