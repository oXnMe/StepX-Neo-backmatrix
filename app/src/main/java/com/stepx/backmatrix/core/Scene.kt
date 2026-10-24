package com.stepx.backmatrix.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 391 字节帧缓冲上的绘图原语。
 *
 * 实时工具（监控、传感器、沙盒）都需要画点、线、圆圈、文字，
 * 与其每个工具各写一遍，不如集中在这里，顺便统一越界处理。
 * 所有坐标都是"点阵坐标"，x∈[0,17) y∈[0,23)，原点左上。
 */
object Scene {

    fun clear(b: ByteArray, v: Int = 0) {
        java.util.Arrays.fill(b, Dot.clamp(v).toByte())
    }

    fun get(b: ByteArray, x: Int, y: Int): Int =
        if (x in 0 until Dot.W && y in 0 until Dot.H) b[y * Dot.W + x].toInt() and 0xFF else 0

    fun put(b: ByteArray, x: Int, y: Int, v: Int) {
        if (!Dot.visible(x, y)) return
        b[y * Dot.W + x] = Dot.clamp(v).toByte()
    }

    /** 取最大值写入：多个图层叠加时不会互相压暗。 */
    fun max(b: ByteArray, x: Int, y: Int, v: Int) {
        if (!Dot.visible(x, y)) return
        val i = y * Dot.W + x
        val cur = b[i].toInt() and 0xFF
        val nv = Dot.clamp(v)
        if (nv > cur) b[i] = nv.toByte()
    }

    /** 加法饱和写入，用于发光叠加。 */
    fun add(b: ByteArray, x: Int, y: Int, v: Int) {
        if (!Dot.visible(x, y)) return
        val i = y * Dot.W + x
        b[i] = Dot.clamp((b[i].toInt() and 0xFF) + v).toByte()
    }

    fun hLine(b: ByteArray, x0: Int, x1: Int, y: Int, v: Int) {
        val a = minOf(x0, x1); val z = maxOf(x0, x1)
        for (x in a..z) max(b, x, y, v)
    }

    fun vLine(b: ByteArray, x: Int, y0: Int, y1: Int, v: Int) {
        val a = minOf(y0, y1); val z = maxOf(y0, y1)
        for (y in a..z) max(b, x, y, v)
    }

    fun line(b: ByteArray, x0: Float, y0: Float, x1: Float, y1: Float, v: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val steps = maxOf(abs(dx), abs(dy)).roundToInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            max(b, (x0 + dx * t).roundToInt(), (y0 + dy * t).roundToInt(), v)
        }
    }

    fun rect(b: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int, v: Int, fill: Boolean = false) {
        if (fill) {
            for (y in minOf(y0, y1)..maxOf(y0, y1)) hLine(b, x0, x1, y, v)
        } else {
            hLine(b, x0, x1, y0, v); hLine(b, x0, x1, y1, v)
            vLine(b, x0, y0, y1, v); vLine(b, x1, y0, y1, v)
        }
    }

    /** 按椭圆画实心/空心，pointy 点阵下比正圆更省事也更好看。 */
    fun ellipse(b: ByteArray, cx: Float, cy: Float, rx: Float, ry: Float, v: Int, fill: Boolean = true) {
        val x0 = (cx - rx).roundToInt(); val x1 = (cx + rx).roundToInt()
        val y0 = (cy - ry).roundToInt(); val y1 = (cy + ry).roundToInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val nx = (x - cx) / (rx.coerceAtLeast(0.001f))
                val ny = (y - cy) / (ry.coerceAtLeast(0.001f))
                val d = nx * nx + ny * ny
                if (fill) {
                    if (d <= 1f) max(b, x, y, v)
                } else {
                    if (d <= 1f && d >= 0.45f) max(b, x, y, v)
                }
            }
        }
    }

    fun ring(b: ByteArray, cx: Float, cy: Float, r: Float, v: Int) =
        ellipse(b, cx, cy, r, r, v, fill = false)

    /**
     * 单像素宽的正圆轮廓。
     *
     * [ring] 画的是有宽度的环带（半径的 1/3 那么厚），画大尺寸的光环好看，
     * 但做硬币边框、仪表盘这类细线时会糊成一团，所以另外给一个。
     */
    fun circle(b: ByteArray, cx: Float, cy: Float, r: Float, v: Int) {
        val steps = (r * 10).roundToInt().coerceIn(24, 96)
        for (i in 0 until steps) {
            val a = i * 2.0 * PI / steps
            max(
                b,
                (cx + sin(a).toFloat() * r).roundToInt(),
                (cy - cos(a).toFloat() * r).roundToInt(),
                v
            )
        }
    }

    /**
     * 箭头：angle 以"正北"为 0，顺时针为正（与航向角一致）。
     */
    fun arrow(b: ByteArray, cx: Float, cy: Float, angle: Float, len: Float, v: Int, head: Float = 3.2f) {
        val dx = sin(angle); val dy = -cos(angle)
        val tx = cx + dx * len; val ty = cy + dy * len
        line(b, cx, cy, tx, ty, v)
        for (s in floatArrayOf(-0.55f, 0.55f)) {
            val a = angle + s
            line(b, tx, ty, tx - sin(a) * head, ty + cos(a) * head, v)
        }
    }

    fun putFrame(dst: ByteArray, src: ByteArray, dx: Int = 0, dy: Int = 0) {
        for (y in 0 until Dot.H) for (x in 0 until Dot.W) {
            val v = src[y * Dot.W + x].toInt() and 0xFF
            if (v > 0) max(dst, x + dx, y + dy, v)
        }
    }
}

/**
 * 3×5 像素字体。17 点宽一行最多放 5 个字，所以只放必要的东西：
 * 字母（大写）、数字、少量符号。scale=3 时 9×15，正好当骰子点数用。
 */
object PixelText {

    /** 按 5 行 3 列书写，比压成一串好看也更不容易写错位宽。 */
    private fun g(a: String, b: String, c: String, d: String, e: String) = a + b + c + d + e

    val GLYPH_W = 3
    val GLYPH_H = 5
    private const val GAP = 1

    // 36 个字形中 28 个与 backscreen-toolbox 的 ToolFrames.LETTERS / NUM 逐位相同（MIT），
    // 出处见 NOTICE.md；2/3/5/6/9 与 N/Q/W 是本仓库重画的。
    private val GLYPHS: Map<Char, String> = mapOf(
        '0' to g("###", "#.#", "#.#", "#.#", "###"),
        '1' to g(".#.", "##.", ".#.", ".#.", "###"),
        '2' to g("##.", "..#", ".#.", "#..", "###"),
        '3' to g("##.", "..#", ".#.", "..#", "##."),
        '4' to g("#.#", "#.#", "###", "..#", "..#"),
        '5' to g("###", "#..", "##.", "..#", "##."),
        '6' to g(".##", "#..", "###", "#.#", "###"),
        '7' to g("###", "..#", ".#.", ".#.", ".#."),
        '8' to g("###", "#.#", "###", "#.#", "###"),
        '9' to g("###", "#.#", "###", "..#", "##."),
        'A' to g(".#.", "#.#", "###", "#.#", "#.#"),
        'B' to g("##.", "#.#", "##.", "#.#", "##."),
        'C' to g(".##", "#..", "#..", "#..", ".##"),
        'D' to g("##.", "#.#", "#.#", "#.#", "##."),
        'E' to g("###", "#..", "##.", "#..", "###"),
        'F' to g("###", "#..", "##.", "#..", "#.."),
        'G' to g(".##", "#..", "#.#", "#.#", ".##"),
        'H' to g("#.#", "#.#", "###", "#.#", "#.#"),
        'I' to g("###", ".#.", ".#.", ".#.", "###"),
        'J' to g("..#", "..#", "..#", "#.#", ".#."),
        'K' to g("#.#", "#.#", "##.", "#.#", "#.#"),
        'L' to g("#..", "#..", "#..", "#..", "###"),
        // M / N / W 在 3 点宽里最容易糊成同一个字形，三者必须画得有区别。
        // 现在 M 的密处在顶部、W 的密处在底部（互为上下翻转），N 靠右上角那一笔区分
        'M' to g("#.#", "###", "###", "#.#", "#.#"),
        'N' to g("#.#", "##.", "#.#", "#.#", "#.#"),
        'O' to g(".#.", "#.#", "#.#", "#.#", ".#."),
        'P' to g("##.", "#.#", "##.", "#..", "#.."),
        'Q' to g(".#.", "#.#", "#.#", "###", "..#"),
        'R' to g("##.", "#.#", "##.", "#.#", "#.#"),
        'S' to g(".##", "#..", ".#.", "..#", "##."),
        'T' to g("###", ".#.", ".#.", ".#.", ".#."),
        'U' to g("#.#", "#.#", "#.#", "#.#", "###"),
        'V' to g("#.#", "#.#", "#.#", "#.#", ".#."),
        'W' to g("#.#", "#.#", "#.#", "###", "#.#"),
        'X' to g("#.#", "#.#", ".#.", "#.#", "#.#"),
        'Y' to g("#.#", "#.#", ".#.", ".#.", ".#."),
        'Z' to g("###", "..#", ".#.", "#..", "###"),
        ' ' to g("...", "...", "...", "...", "..."),
        '.' to g("...", "...", "...", "...", ".#."),
        '!' to g(".#.", ".#.", ".#.", "...", ".#."),
        ':' to g("...", ".#.", "...", ".#.", "..."),
        '-' to g("...", "...", "###", "...", "..."),
        '/' to g("..#", "..#", ".#.", "#..", "#.."),
        '?' to g("##.", "..#", ".#.", "...", ".#."),
        '+' to g("...", ".#.", "###", ".#.", "..."),
        '%' to g("#.#", "..#", ".#.", "#..", "#.#"),
        '>' to g("#..", ".#.", "..#", ".#.", "#..")
    )

    /**
     * 大号数字：4 宽 × 9 高。
     *
     * FPS 常常是 165 这种三位数。3×5 字体放大到 scale=2 每个字符 6 点宽，
     * 三个就是 22 点，比整块面板还宽，只能退成一行小字——高刷屏用户就看不清楚了。
     * 这套 4 宽的字形三个加起来 `4*3+1*2 = 14` 点，17 点的面板放得下，
     * 还留得下右上角一颗状态标记。
     */
    const val BIG_W = 4
    const val BIG_H = 9
    private const val BIG_GAP = 1

    private fun n(vararg rows: String) = rows.joinToString("")

    private val BIG_DIGITS: Map<Char, String> = mapOf(
        '0' to n(".##.", "#..#", "#..#", "#..#", "#..#", "#..#", "#..#", "#..#", ".##."),
        '1' to n(".#..", "##..", ".#..", ".#..", ".#..", ".#..", ".#..", ".#..", "###."),
        '2' to n(".##.", "#..#", "...#", "...#", "..#.", ".#..", "#...", "#...", "####"),
        '3' to n(".###", "#..#", "...#", "..##", "...#", "...#", "...#", "#..#", ".###"),
        '4' to n("...#", "..##", ".#.#", "#..#", "#..#", "####", "...#", "...#", "...#"),
        '5' to n("####", "#...", "#...", "#...", "###.", "...#", "...#", "#..#", ".##."),
        '6' to n(".##.", "#...", "#...", "#...", "###.", "#..#", "#..#", "#..#", ".##."),
        '7' to n("####", "...#", "...#", "..#.", "..#.", ".#..", ".#..", ".#..", ".#.."),
        '8' to n(".##.", "#..#", "#..#", "#..#", ".##.", "#..#", "#..#", "#..#", ".##."),
        '9' to n(".##.", "#..#", "#..#", "#..#", ".###", "...#", "...#", "..##", ".##."),
        '-' to n("....", "....", "....", "....", "####", "....", "....", "....", "....")
    )

    fun bigNumWidth(text: String, scale: Int = 1): Int =
        if (text.isEmpty()) 0 else text.length * (BIG_W + BIG_GAP) * scale - BIG_GAP * scale

    fun bigNumHeight(scale: Int = 1): Int = BIG_H * scale

    /** 大号数字，只认 0-9 和 `-`（FPS 用不上别的）。返回绘制宽度。 */
    fun drawBigNum(b: ByteArray, text: String, x: Int, y: Int, scale: Int = 1, v: Int = 255): Int {
        var cx = x
        for (ch in text) {
            val g = BIG_DIGITS[ch]
            if (g == null) {
                cx += (BIG_W + BIG_GAP) * scale
                continue
            }
            for (row in 0 until BIG_H) {
                for (col in 0 until BIG_W) {
                    if (g[row * BIG_W + col] != '#') continue
                    for (sy in 0 until scale) for (sx in 0 until scale) {
                        Scene.max(b, cx + col * scale + sx, y + row * scale + sy, v)
                    }
                }
            }
            cx += (BIG_W + BIG_GAP) * scale
        }
        return bigNumWidth(text, scale)
    }

    init {
        // 位宽写错会让整行字歪掉，启动时就卡住比上线后才发现好
        GLYPHS.forEach { (c, v) ->
            require(v.length == GLYPH_W * GLYPH_H) { "字形 $c 长度应为 15，实际 ${v.length}" }
        }
        BIG_DIGITS.forEach { (c, v) ->
            require(v.length == BIG_W * BIG_H) { "大字形 $c 长度应为 ${BIG_W * BIG_H}，实际 ${v.length}" }
        }
    }

    fun width(text: String, scale: Int = 1): Int =
        if (text.isEmpty()) 0 else text.length * (GLYPH_W + GAP) * scale - GAP * scale

    fun height(scale: Int = 1): Int = GLYPH_H * scale

    /** 在 (x,y) 左上角绘制，返回实际宽度。erase=true 时把字挖成洞（用于在亮底上刻字）。 */
    fun draw(b: ByteArray, text: String, x: Int, y: Int, scale: Int = 1, v: Int = 255, erase: Boolean = false): Int {
        var cx = x
        for (ch in text.uppercase()) {
            val g = GLYPHS[ch] ?: GLYPHS[' ']!!
            for (row in 0 until GLYPH_H) {
                for (col in 0 until GLYPH_W) {
                    if (g[row * GLYPH_W + col] != '#') continue
                    for (sy in 0 until scale) for (sx in 0 until scale) {
                        if (erase) Scene.put(b, cx + col * scale + sx, y + row * scale + sy, 0)
                        else Scene.max(b, cx + col * scale + sx, y + row * scale + sy, v)
                    }
                }
            }
            cx += (GLYPH_W + GAP) * scale
        }
        return if (text.isEmpty()) 0 else cx - x - GAP * scale
    }

    fun drawCentered(
        b: ByteArray,
        text: String,
        cy: Int,
        scale: Int = 1,
        v: Int = 255,
        erase: Boolean = false
    ) {
        draw(b, text, (Dot.W - width(text, scale)) / 2, cy, scale, v, erase)
    }
}

/** 几个常用的大图形，单独画比用字体清楚。 */
object GlyphArt {

    /** 打勾，用于构建成功。 */
    fun check(b: ByteArray, cx: Float, cy: Float, size: Float, v: Int) {
        Scene.line(b, cx - size * 0.55f, cy, cx - size * 0.12f, cy + size * 0.45f, v)
        Scene.line(b, cx - size * 0.12f, cy + size * 0.45f, cx + size * 0.6f, cy - size * 0.5f, v)
        Scene.line(b, cx - size * 0.55f, cy - 1f, cx - size * 0.12f, cy + size * 0.45f - 1f, v)
    }

    /** 叉，用于构建失败。 */
    fun cross(b: ByteArray, cx: Float, cy: Float, size: Float, v: Int) {
        for (o in floatArrayOf(0f, 1f)) {
            Scene.line(b, cx - size * 0.5f + o, cy - size * 0.5f + o, cx + size * 0.5f, cy + size * 0.5f, v)
            Scene.line(b, cx + size * 0.5f - o, cy - size * 0.5f + o, cx - size * 0.5f, cy + size * 0.5f, v)
        }
    }

    /** 满屏大感叹号，用于宕机告警。 */
    fun bang(b: ByteArray, v: Int) {
        Scene.rect(b, 7, 2, 9, 14, v, fill = true)
        Scene.rect(b, 7, 17, 9, 20, v, fill = true)
    }

    /**
     * 心形。brokenGap > 0 时从中间撕开成两半，数值越大裂得越开。
     *
     * 13×11 实心：裂开后左右两半各自外移，中间留一道锯齿缝。
     */
    fun heart(b: ByteArray, v: Int, brokenGap: Int = 0) {
        val rows = arrayOf(
            "..###...###..",
            ".#####.#####.",
            "#############",
            "#############",
            "#############",
            ".###########.",
            "..#########..",
            "...#######...",
            "....#####....",
            ".....###.....",
            "......#......"
        )
        val left = 2
        val top = 5
        val gap = brokenGap.coerceAtLeast(0)
        for (r in rows.indices) {
            for (c in rows[r].indices) {
                if (rows[r][c] != '#') continue
                if (gap > 0) {
                    // 锯齿边：每一行的裂缝位置左右错开，撕开的样子才不像刀切
                    val edge = 6 + (r % 3) - 1
                    if (c in (edge - gap + 1)..(edge + gap)) continue
                }
                val shift = when {
                    gap == 0 -> 0
                    c < 6 -> -gap
                    else -> gap
                }
                Scene.max(b, left + c + shift, top + r, v)
            }
        }
    }
}
