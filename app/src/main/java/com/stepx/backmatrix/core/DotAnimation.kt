package com.stepx.backmatrix.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 一段已经转换好的点阵动画。每帧固定 391 字节（0..255 灰度），
 * 31 秒 12fps 的动画也只有 140KB 左右。
 */
class DotAnimation(
    val name: String,
    val fps: Int,
    val loop: Boolean,
    val frames: List<ByteArray>
) {
    val frameCount: Int get() = frames.size
    val durationMs: Long get() = if (fps <= 0) 0 else frames.size * 1000L / fps

    fun frameAt(index: Int): ByteArray {
        if (frames.isEmpty()) return Dot.blank()
        val i = ((index % frames.size) + frames.size) % frames.size
        return frames[i]
    }

    fun describe(): String = Dot.describe(fps, frameCount)

    /** 展开成乒乓循环（来回播放），用于让短动画看起来更顺。 */
    fun pingPong(): DotAnimation {
        if (frames.size < 3) return this
        val list = ArrayList<ByteArray>(frames.size * 2 - 2)
        list.addAll(frames)
        // 去掉首尾两个重复帧，接缝才不会卡一拍
        for (i in frames.size - 2 downTo 1) list.add(frames[i])
        return DotAnimation(name, fps, loop, list)
    }

    fun withName(newName: String) = DotAnimation(newName, fps, loop, frames)

    companion object {
        /** .bsa = BackScreen Animation，12 字节头 + 391 字节/帧 */
        private const val MAGIC = "BSA1"
        private const val VERSION = 1

        fun encode(anim: DotAnimation, out: OutputStream) {
            val nameBytes = anim.name.toByteArray(Charsets.UTF_8)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(VERSION)
            out.write(if (anim.loop) 1 else 0)
            out.write(anim.fps.coerceIn(1, 60))
            out.write(Dot.W)
            out.write(Dot.H)
            out.write(anim.frames.size and 0xFF)
            out.write((anim.frames.size shr 8) and 0xFF)
            out.write(nameBytes.size and 0xFF)
            out.write((nameBytes.size shr 8) and 0xFF)
            out.write(nameBytes)
            for (f in anim.frames) {
                require(f.size == Dot.PIXELS) { "每帧必须是 17x23" }
                out.write(f)
            }
            out.flush()
        }

        fun toBytes(anim: DotAnimation): ByteArray {
            val bos = ByteArrayOutputStream()
            encode(anim, bos)
            return bos.toByteArray()
        }

        fun decode(input: InputStream): DotAnimation {
            val data = input.readBytes()
            require(data.size >= 12) { "动画文件过短" }
            require(String(data, 0, 4, Charsets.US_ASCII) == MAGIC) { "不是点阵动画文件" }
            val version = data[4].toInt() and 0xFF
            require(version == VERSION) { "动画文件版本不支持：$version" }
            val loop = (data[5].toInt() and 0xFF) != 0
            val fps = (data[6].toInt() and 0xFF).coerceAtLeast(1)
            val w = data[7].toInt() and 0xFF
            val h = data[8].toInt() and 0xFF
            require(w == Dot.W && h == Dot.H) { "动画尺寸 ${w}x$h 与背屏不符" }
            val count = (data[9].toInt() and 0xFF) or ((data[10].toInt() and 0xFF) shl 8)
            val nameLen = (data[11].toInt() and 0xFF) or ((data[12].toInt() and 0xFF) shl 8)
            var pos = 13
            val name = if (nameLen > 0 && pos + nameLen <= data.size) {
                String(data, pos, nameLen, Charsets.UTF_8)
            } else "动画"
            pos += nameLen
            val frames = ArrayList<ByteArray>(count)
            for (i in 0 until count) {
                if (pos + Dot.PIXELS > data.size) break
                frames.add(data.copyOfRange(pos, pos + Dot.PIXELS))
                pos += Dot.PIXELS
            }
            require(frames.isNotEmpty()) { "动画里没有任何帧" }
            return DotAnimation(name, fps, loop, frames)
        }
    }
}
