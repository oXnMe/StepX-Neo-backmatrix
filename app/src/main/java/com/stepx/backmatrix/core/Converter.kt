package com.stepx.backmatrix.core

/** 取景方式 */
enum class Fit { COVER, CONTAIN, STRETCH }

/**
 * 一次转换的全部参数。默认值针对“白底黑线”的线稿（GIF 表情包最常见）调过：
 * 反相后按覆盖度取亮度，8 级灰度不带抖动，保留轮廓又能看出层次。
 */
data class ToneSettings(
    val fit: Fit = Fit.COVER,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val invert: Boolean = true,
    val black: Float = 0.08f,
    val white: Float = 0.40f,
    val gamma: Float = 1.30f,
    val levels: Int = 8,
    val dither: Boolean = false,
    val brightness: Float = 1f,
    val fps: Int = 12,
    val loop: Boolean = true,
    val pingPong: Boolean = false
) {
    companion object {
        /** 白底黑线的线稿/表情包：反相后压黑场，把压缩噪点挡掉。 */
        fun lineArt() = ToneSettings(invert = true, black = 0.08f, white = 0.40f, gamma = 1.30f, levels = 8)

        /** 实拍或彩色视频：不反相，保留中间调。 */
        fun photo() = ToneSettings(invert = false, black = 0.02f, white = 0.90f, gamma = 1.00f, levels = 16)
    }
}

/**
 * 把一帧 ARGB 图像映射成 17x23 的灰度点阵。
 *
 * 关键点是“按面积平均”而不是最近邻采样：点阵只有 391 个点，
 * 最近邻会把细线整条丢掉，面积平均能把线稿的墨量保留成亮度。
 */
object Converter {

    fun convert(src: IntArray, srcW: Int, srcH: Int, s: ToneSettings): ByteArray {
        require(src.size >= srcW * srcH) { "像素数量与尺寸不符" }
        val out = ByteArray(Dot.PIXELS)

        // 1) 计算“网格坐标”与“源坐标”的对应矩形
        if (s.fit == Fit.COVER) {
            val gridAspect = Dot.W.toFloat() / Dot.H
            val srcAspect = srcW.toFloat() / srcH
            val cw0: Float
            val ch0: Float
            if (srcAspect > gridAspect) { ch0 = srcH.toFloat(); cw0 = srcH * gridAspect }
            else { cw0 = srcW.toFloat(); ch0 = srcW / gridAspect }
            val zoom = s.zoom.coerceIn(0.2f, 4f)
            val zw = (cw0 / zoom).coerceAtMost(srcW.toFloat())
            val zh = (ch0 / zoom).coerceAtMost(srcH.toFloat())
            val cx = srcW / 2f + s.offsetX.coerceIn(-1f, 1f) * (srcW - zw) / 2f
            val cy = srcH / 2f + s.offsetY.coerceIn(-1f, 1f) * (srcH - zh) / 2f
            val x0 = (cx - zw / 2f).coerceIn(0f, srcW - zw)
            val y0 = (cy - zh / 2f).coerceIn(0f, srcH - zh)
            return sample(
                src, srcW, srcH, s, out,
                x0, y0, zw, zh,
                0f, 0f, Dot.W.toFloat(), Dot.H.toFloat()
            )
        }
        val gx0: Float
        val gy0: Float
        val gx1: Float
        val gy1: Float
        when (s.fit) {
            Fit.STRETCH -> { gx0 = 0f; gy0 = 0f; gx1 = Dot.W.toFloat(); gy1 = Dot.H.toFloat() }
            Fit.CONTAIN -> {
                val gridAspect = Dot.W.toFloat() / Dot.H
                val srcAspect = srcW.toFloat() / srcH
                if (srcAspect > gridAspect) {
                    gx0 = 0f; gx1 = Dot.W.toFloat()
                    val gh = Dot.W / srcAspect
                    gy0 = (Dot.H - gh) / 2f; gy1 = gy0 + gh
                } else {
                    gy0 = 0f; gy1 = Dot.H.toFloat()
                    val gw = Dot.H * srcAspect
                    gx0 = (Dot.W - gw) / 2f; gx1 = gx0 + gw
                }
            }
            Fit.COVER -> { gx0 = 0f; gy0 = 0f; gx1 = Dot.W.toFloat(); gy1 = Dot.H.toFloat() }
        }
        return sample(src, srcW, srcH, s, out, 0f, 0f, srcW.toFloat(), srcH.toFloat(), gx0, gy0, gx1, gy1)
    }

    /**
     * 在网格矩形 [gx0,gy0,gx1,gy1] 内混合源矩形 [sx0,sy0,sw,sh] 的像素。
     * 网格外的点保持 0（留白），这样 CONTAIN 会自然得到黑边。
     */
    private fun sample(
        src: IntArray, srcW: Int, srcH: Int, s: ToneSettings, out: ByteArray,
        sx0: Float, sy0: Float, sw: Float, sh: Float,
        gx0: Float, gy0: Float, gx1: Float, gy1: Float
    ): ByteArray {
        val bw = 1f / (s.white - s.black).let { if (kotlin.math.abs(it) < 1e-4f) 1f else it }
        val gamma = s.gamma.coerceIn(0.2f, 4f)
        val levels = s.levels.coerceIn(2, 256)
        val step = if (levels > 1) levels - 1 else 1

        for (gy in 0 until Dot.H) {
            val cy0 = maxOf(gy.toFloat(), gy0)
            val cy1 = minOf(gy + 1f, gy1)
            if (cy1 - cy0 <= 1e-4f) continue
            val ty0 = (cy0 - gy0) / (gy1 - gy0)
            val ty1 = (cy1 - gy0) / (gy1 - gy0)
            val ry0 = sy0 + ty0 * sh
            val ry1 = sy0 + ty1 * sh

            for (gx in 0 until Dot.W) {
                val cx0 = maxOf(gx.toFloat(), gx0)
                val cx1 = minOf(gx + 1f, gx1)
                if (cx1 - cx0 <= 1e-4f) continue
                val tx0 = (cx0 - gx0) / (gx1 - gx0)
                val tx1 = (cx1 - gx0) / (gx1 - gx0)
                val rx0 = sx0 + tx0 * sw
                val rx1 = sx0 + tx1 * sw

                val x0 = rx0.toInt().coerceIn(0, srcW - 1)
                val x1 = kotlin.math.ceil(rx1).toInt().coerceIn(x0 + 1, srcW)
                val y0 = ry0.toInt().coerceIn(0, srcH - 1)
                val y1 = kotlin.math.ceil(ry1).toInt().coerceIn(y0 + 1, srcH)

                var sum = 0f
                var count = 0
                for (y in y0 until y1) {
                    val row = y * srcW
                    for (x in x0 until x1) {
                        val argb = src[row + x]
                        val a = (argb ushr 24) and 0xFF
                        if (a < 8) { sum += 1f; count++; continue } // 透明视为留白（白底）
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                        count++
                    }
                }
                if (count == 0) continue
                var v = sum / count
                if (s.invert) v = 1f - v
                v = (v - s.black) * bw
                v = v.coerceIn(0f, 1f)
                if (gamma != 1f) v = Math.pow(v.toDouble(), gamma.toDouble()).toFloat()

                if (s.dither && levels > 1 && levels < 256) {
                    val t = (Dot.BAYER8[gy and 7][gx and 7] + 0.5f) / 64f - 0.5f
                    v = (v + t / step).coerceIn(0f, 1f)
                }
                var q = if (levels >= 256) v else Math.round(v * step) / step.toFloat()
                q *= s.brightness.coerceIn(0f, 1f)
                out[gy * Dot.W + gx] = (q * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 平均亮度，用来猜这是白底线稿还是普通画面。 */
    fun meanLuma(src: IntArray): Float {
        if (src.isEmpty()) return 0f
        var sum = 0f
        for (p in src) {
            val a = (p ushr 24) and 0xFF
            if (a < 8) { sum += 1f; continue }
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        return sum / src.size
    }

    /** 生成一张“源图在 17x23 里会变成什么”的示意网格，用于取景预览。 */
    fun preview(src: IntArray, srcW: Int, srcH: Int, s: ToneSettings): ByteArray =
        convert(src, srcW, srcH, s.copy(levels = 8, dither = false, brightness = 1f))
}
