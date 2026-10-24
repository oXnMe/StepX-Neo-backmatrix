package com.stepx.backmatrix.core

import java.io.InputStream

/**
 * 纯 Kotlin 的 GIF89a 解码器（无第三方依赖）。
 *
 * 逐帧合成到一整幅 ARGB 画布后回调，调用方可以立刻把这一帧转成 391 字节的点阵，
 * 因此 700 帧的大 GIF 也只占两幅画布的内存。
 */
object GifDecoder {

    /**
     * @param onFrame 每合成完一帧回调一次：(整幅画布的 ARGB 像素, 宽, 高, 该帧延时毫秒)。
     *                返回 false 可提前结束。
     * @return 图片宽高，解码失败时抛 IllegalArgumentException。
     */
    fun decode(input: InputStream, onFrame: (IntArray, Int, Int, Int) -> Boolean): Pair<Int, Int> {
        val data = input.readBytes()
        val r = Reader(data)

        val sig = String(CharArray(6).also { for (i in 0 until 6) it[i] = r.u8().toChar() })
        require(sig == "GIF87a" || sig == "GIF89a") { "不是有效的 GIF 文件" }

        val width = r.u16()
        val height = r.u16()
        require(width in 1..4096 && height in 1..4096) { "GIF 尺寸异常：${width}x$height" }
        val packed = r.u8()
        r.u8() // background index
        r.u8() // pixel aspect ratio

        var globalPalette: IntArray? = null
        if (packed and 0x80 != 0) {
            globalPalette = readPalette(r, 2 shl (packed and 0x07))
        }

        val canvas = IntArray(width * height)
        var snapshot = IntArray(width * height)
        var prevDisposal = 0
        var prevRect: IntArray? = null

        var transparent = -1
        var disposal = 0
        var delayCs = -1
        var stop = false

        while (!stop) {
            val block = r.u8OrNull() ?: break
            when (block) {
                0x3B -> break // trailer
                0x21 -> { // extension
                    when (r.u8OrNull() ?: break) {
                        0xF9 -> {
                            r.u8() // block size (usually 4)
                            val flags = r.u8()
                            delayCs = r.u16()
                            val tIndex = r.u8()
                            transparent = if (flags and 0x01 != 0) tIndex else -1
                            disposal = (flags shr 2) and 0x07
                            r.skipBlocks()
                        }
                        0xFF -> { // application extension (NETSCAPE 循环等)：11 字节头 + 子块
                            val size = r.u8()
                            r.skip(size)
                            r.skipBlocks()
                        }
                        else -> {
                            r.u8()
                            r.skipBlocks()
                        }
                    }
                }
                0x2C -> { // image descriptor
                    val left = r.u16()
                    val top = r.u16()
                    val fw = r.u16()
                    val fh = r.u16()
                    val ipacked = r.u8()
                    val localPalette = if (ipacked and 0x80 != 0) readPalette(r, 2 shl (ipacked and 0x07)) else null
                    val interlaced = ipacked and 0x40 != 0
                    val palette = localPalette ?: globalPalette ?: IntArray(2) { if (it == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

                    val minCodeSize = r.u8().coerceAtLeast(2)
                    val lzw = r.readSubBlocks()
                    val indices = ByteArray(fw * fh)
                    Lzw(minCodeSize, lzw).decode(indices)

                    // 上一帧的处置方式先作用在画布上
                    if (prevDisposal == 2) {
                        prevRect?.let { rect -> clear(canvas, width, height, rect) }
                    } else if (prevDisposal == 3) {
                        System.arraycopy(snapshot, 0, canvas, 0, canvas.size)
                    }

                    if (disposal == 3) snapshot = canvas.copyOf()

                    blit(canvas, width, height, indices, fw, fh, left, top, palette, transparent, interlaced)

                    val ms = if (delayCs <= 0) 40 else (delayCs * 10).coerceAtLeast(20)
                    if (!onFrame(canvas, width, height, ms)) { stop = true }

                    prevDisposal = if (disposal == 0) 1 else disposal
                    prevRect = intArrayOf(left, top, fw, fh)
                    transparent = -1
                    disposal = 0
                    delayCs = -1
                }
                else -> {
                    // 未知块，尽量跳过而不是整段失败
                    break
                }
            }
        }
        return width to height
    }

    private fun readPalette(r: Reader, count: Int): IntArray {
        val p = IntArray(count)
        for (i in 0 until count) {
            val rr = r.u8(); val gg = r.u8(); val bb = r.u8()
            p[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return p
    }

    private fun clear(canvas: IntArray, w: Int, h: Int, rect: IntArray) {
        val (x0, y0, rw, rh) = listOf(rect[0], rect[1], rect[2], rect[3])
        for (y in y0 until (y0 + rh).coerceAtMost(h)) {
            if (y < 0) continue
            val row = y * w
            for (x in x0 until (x0 + rw).coerceAtMost(w)) {
                if (x >= 0) canvas[row + x] = 0
            }
        }
    }

    private fun blit(
        canvas: IntArray, w: Int, h: Int,
        indices: ByteArray, fw: Int, fh: Int,
        left: Int, top: Int,
        palette: IntArray, transparent: Int, interlaced: Boolean
    ) {
        var srcRow = 0
        val order: IntArray = if (interlaced) {
            val list = ArrayList<Int>(fh)
            var y = 0
            while (y < fh) { list.add(y); y += 8 }
            y = 4
            while (y < fh) { list.add(y); y += 8 }
            y = 2
            while (y < fh) { list.add(y); y += 4 }
            y = 1
            while (y < fh) { list.add(y); y += 2 }
            list.toIntArray()
        } else {
            IntArray(fh) { it }
        }
        for (row in order) {
            val dy = top + row
            if (dy in 0 until h) {
                val cRow = dy * w
                val sRow = srcRow * fw
                for (x in 0 until fw) {
                    val idx = indices[sRow + x].toInt() and 0xFF
                    if (idx == transparent) continue
                    val dx = left + x
                    if (dx in 0 until w) canvas[cRow + dx] = palette.getOrElse(idx) { 0xFF000000.toInt() }
                }
            }
            srcRow++
        }
    }

    private class Reader(private val d: ByteArray) {
        var pos = 0
        fun u8(): Int {
            require(pos < d.size) { "GIF 数据不完整" }
            return d[pos++].toInt() and 0xFF
        }
        fun u8OrNull(): Int? = if (pos < d.size) d[pos++].toInt() and 0xFF else null
        fun u16(): Int {
            val lo = u8(); val hi = u8()
            return lo or (hi shl 8)
        }
        fun skipBlocks() {
            while (true) {
                val len = u8OrNull() ?: return
                if (len == 0) return
                pos += len
                if (pos > d.size) return
            }
        }

        fun skip(count: Int) {
            pos = (pos + count).coerceAtMost(d.size)
        }
        fun readSubBlocks(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val len = u8OrNull() ?: break
                if (len == 0) break
                val end = (pos + len).coerceAtMost(d.size)
                out.write(d, pos, end - pos)
                pos = end
            }
            return out.toByteArray()
        }
    }

    /** 标准 GIF LZW 解码 */
    private class Lzw(private val minCodeSize: Int, private val data: ByteArray) {
        private var bitPos = 0

        private fun readBits(size: Int): Int {
            var result = 0
            for (i in 0 until size) {
                val byteIndex = bitPos ushr 3
                if (byteIndex >= data.size) return -1
                val bit = (data[byteIndex].toInt() ushr (bitPos and 7)) and 1
                result = result or (bit shl i)
                bitPos++
            }
            return result
        }

        fun decode(out: ByteArray) {
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            val prefix = IntArray(4096)
            val suffix = IntArray(4096)
            val stack = IntArray(4097)
            for (i in 0 until clear) { prefix[i] = -1; suffix[i] = i }

            var codeSize = minCodeSize + 1
            var next = eoi + 1
            var old = -1
            var first = 0
            var outPos = 0

            while (outPos < out.size) {
                val code = readBits(codeSize)
                if (code < 0 || code == eoi) break
                if (code == clear) {
                    codeSize = minCodeSize + 1
                    next = eoi + 1
                    old = -1
                    continue
                }
                var sp = 0
                if (old == -1) {
                    if (code >= next) break
                    out[outPos++] = suffix[code].toByte()
                    first = suffix[code]
                    old = code
                    continue
                }
                var cur = code
                if (code >= next) {
                    stack[sp++] = first
                    cur = old
                }
                while (cur >= clear && sp <= 4096) {
                    stack[sp++] = suffix[cur]
                    cur = prefix[cur]
                    if (cur < 0) break
                }
                first = suffix[cur]
                stack[sp++] = first

                if (next < 4096) {
                    prefix[next] = old
                    suffix[next] = first
                    next++
                    if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                old = code
                while (sp > 0 && outPos < out.size) out[outPos++] = stack[--sp].toByte()
            }
        }
    }
}
