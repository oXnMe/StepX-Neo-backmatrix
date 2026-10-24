package com.stepx.backmatrix.core

/**
 * PZ261A 背屏的点阵尺寸与通用工具。
 *
 * 厂商接口 setBackScreenAgentFrame(int[], Bundle) 需要 17*23=391 个灰度值（0..255），
 * 索引为 y * 17 + x，原点在左上角。
 */
object Dot {
    const val W = 17
    const val H = 23
    const val PIXELS = W * H

    /** 横屏/旋转时使用屏幕中央的 17x17 安全区（Rows 3..19）。 */
    const val SAFE_TOP = 3
    const val SAFE_ROWS = 17

    val BAYER8 = arrayOf(
        intArrayOf(0, 48, 12, 60, 3, 51, 15, 63),
        intArrayOf(32, 16, 44, 28, 35, 19, 47, 31),
        intArrayOf(8, 56, 4, 52, 11, 59, 7, 55),
        intArrayOf(40, 24, 36, 20, 43, 27, 39, 23),
        intArrayOf(2, 50, 14, 62, 1, 49, 13, 61),
        intArrayOf(34, 18, 46, 30, 33, 17, 45, 29),
        intArrayOf(10, 58, 6, 54, 9, 57, 5, 53),
        intArrayOf(42, 26, 38, 22, 41, 25, 37, 21)
    )

    fun blank(): ByteArray = ByteArray(PIXELS)

    fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * 这个点是否真的亮得出来。
     *
     * 面板四角是圆角：首末行各只有 13 个点（x=2..14），第 1 行与倒数第 2 行 15 个点，
     * 中间 19 行完整。[Scene] 的写入原语都会查这里，画到角上的内容自动被裁掉。
     */
    fun visible(x: Int, y: Int): Boolean {
        if (x < 0 || x >= W || y < 0 || y >= H) return false
        return when (y) {
            0, H - 1 -> x in 2..W - 3
            1, H - 2 -> x in 1..W - 2
            else -> true
        }
    }

    /** 某一行的可用列范围（左闭右闭），排版时用来避开圆角。 */
    fun usableX(y: Int): IntRange = when (y) {
        0, H - 1 -> 2..W - 3
        1, H - 2 -> 1..W - 2
        else -> 0..W - 1
    }

    /**
     * 把 17x23 的画面旋转整数个 90°，围绕面板中心 (8, 11) 在中央 17x17 区域内进行。
     * 与背屏固件的方向锚点一致：turns=0 为竖屏原样。
     *
     * 投影与常量同 backscreen-toolbox 的 `FrameOrientation.project()`（MIT），出处见 NOTICE.md。
     */
    fun rotate(scene: ByteArray, turns: Int): ByteArray {
        require(scene.size == PIXELS) { "17x23 required" }
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return scene.copyOf()
        val out = ByteArray(PIXELS)
        for (y in SAFE_TOP until SAFE_TOP + SAFE_ROWS) {
            for (x in 0 until W) {
                val dx = x - 8
                val dy = y - 11
                val rx = when (t) { 1 -> -dy; 2 -> -dx; else -> dy }
                val ry = when (t) { 1 -> dx; 2 -> -dy; else -> -dx }
                val ox = rx + 8
                val oy = ry + 11
                if (ox in 0 until W && oy in 0 until H) out[oy * W + ox] = scene[y * W + x]
            }
        }
        return out
    }

    /**
     * 亮度缩放。factor >= 0.999 时直接返回入参本身，不拷贝；
     * 需要长期持有结果请用 [compose]。
     */
    fun scale(scene: ByteArray, factor: Float): ByteArray {
        if (factor >= 0.999f) return scene
        val f = factor.coerceIn(0f, 1f)
        val out = ByteArray(PIXELS)
        for (i in 0 until PIXELS) {
            val v = ((scene[i].toInt() and 0xFF) * f + 0.5f).toInt()
            out[i] = (if (v > 255) 255 else v).toByte()
        }
        return out
    }

    /**
     * 合成一帧：先按 [factor] 调光，再按 [turns] 做整数个 90° 旋转。
     *
     * 返回值保证不与 [raw] 共享内存。实时工具每帧复用同一块缓冲，
     * 若这里直通返回入参，下游保存的「上一帧」会指向正在被改写的同一块内存，
     * 内容比较退化成自己跟自己比，背屏就会停在第一帧。
     */
    fun compose(raw: ByteArray, factor: Float, turns: Int): ByteArray {
        val t = ((turns % 4) + 4) % 4
        val bright = if (factor >= 0.999f) raw else scale(raw, factor)
        // t == 0 时 bright 可能仍是 raw 本身，显式拷一份；t != 0 时 rotate 内部已新建数组
        return if (t == 0) bright.copyOf() else rotate(bright, t)
    }

    fun toInts(scene: ByteArray): IntArray {
        val a = IntArray(scene.size)
        for (i in scene.indices) a[i] = scene[i].toInt() and 0xFF
        return a
    }

    /** 供 UI 显示的一行摘要，例如 17x23 · 12fps · 4.2s */
    fun describe(fps: Int, frameCount: Int): String {
        val seconds = if (fps <= 0) 0.0 else frameCount.toDouble() / fps
        return String.format(java.util.Locale.US, "%dx%d · %d fps · %.1f s", W, H, fps, seconds)
    }
}
