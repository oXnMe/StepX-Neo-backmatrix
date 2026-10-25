package com.stepx.backmatrix.core

import android.content.Context
import android.os.Build
import java.lang.reflect.Method

/**
 * STEPX PZ261A 的点阵背屏接口。
 *
 * 固件在系统里注册了一个名为 "display_ext" 的服务，公开两个方法：
 *  - setBackScreenAgentFrame(int[] pixels, Bundle extras)：17x23=391 个 0..255 灰度值，传 null 交还背屏
 *  - getBackScreenAnimationInfo()：当前背屏合成器状态（type/state）
 *
 * 没有 AIDL 也没有 SDK，只能反射。所有调用都做了容错，任何异常都只会让本次提交失败，
 * 不会把进程带崩。
 */
object BackScreen {

    private const val SERVICE_NAME = "display_ext"
    const val MODEL = "PZ261A"

    private var manager: Any? = null
    private var setFrame: Method? = null
    private var infoMethod: Method? = null

    @Volatile
    var lastError: String? = null
        private set

    val isTargetModel: Boolean
        get() = Build.MODEL.equals(MODEL, ignoreCase = true)

    fun hasService(context: Context): Boolean = runCatching {
        context.getSystemService(SERVICE_NAME) != null
    }.getOrDefault(false)

    private fun ensure(context: Context): Boolean {
        if (manager != null && setFrame != null) return true
        return try {
            val m = context.getSystemService(SERVICE_NAME) ?: run {
                lastError = "系统里没有 display_ext 服务（非 PZ261A 固件？）"
                return false
            }
            manager = m
            setFrame = m.javaClass.getMethod("setBackScreenAgentFrame", IntArray::class.java, android.os.Bundle::class.java)
            infoMethod = runCatching { m.javaClass.getMethod("getBackScreenAnimationInfo") }.getOrNull()
            if (infoMethod == null) {
                android.util.Log.w(
                    "BackScreen",
                    "没有 getBackScreenAnimationInfo；${m.javaClass.name} 的方法：${
                        m.javaClass.methods.joinToString { it.name }
                    }"
                )
            }
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            manager = null
            setFrame = null
            false
        }
    }

    /**
     * 提交一帧。frame 为 17x23 灰度；传 null 表示交还背屏（停止占用）。
     * @return 是否成功提交
     */
    fun show(context: Context, frame: ByteArray?): Boolean {
        if (frame != null && frame.size != Dot.PIXELS) {
            lastError = "帧尺寸必须是 17x23"
            return false
        }
        if (!ensure(context)) return false
        val pixels = frame?.let { Dot.toInts(it) }
        return try {
            setFrame!!.invoke(manager, pixels, null)
            lastError = null
            true
        } catch (t: Throwable) {
            // 服务可能在系统重启后换过实例，丢弃缓存重试一次
            manager = null
            setFrame = null
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = cause.message ?: cause.javaClass.simpleName
            false
        }
    }

    /** 合成器状态：设置页和本地桥都要这两个值，别让调用方去解析拼好的字符串。 */
    data class Composer(val type: String?, val state: String?)

    fun composer(context: Context): Composer? {
        if (!ensure(context)) return null
        return try {
            // 固件有时会返回 null（背屏没在跑 agent 时），这不是错误，调用方按「读不到」处理
            val info = infoMethod?.invoke(manager) ?: return null
            Composer(
                type = runCatching {
                    info.javaClass.getMethod("getType").invoke(info) as? String
                }.getOrNull(),
                state = runCatching {
                    info.javaClass.getMethod("getState").invoke(info) as? String
                }.getOrNull()
            )
        } catch (t: Throwable) {
            // 别静默吞：合成器状态读不出来时，设置页要能说明原因
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = "合成器状态读取失败：${cause.message ?: cause.javaClass.simpleName}"
            null
        }
    }
}
