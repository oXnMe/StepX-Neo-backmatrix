package com.stepx.backmatrix.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 动画库：内置演示 + 用户导入的动画，都存成 .bsa 文件。 */
object Library {

    const val BUILTIN_ID = "builtin-demo"
    private const val BUILTIN_ASSET = "demo_basketball.bsa"
    private const val PREFS = "library"
    private const val KEY_INDEX = "index"

    data class Entry(
        val id: String,
        val name: String,
        val fps: Int,
        val frameCount: Int,
        val builtin: Boolean,
        val path: String
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "animations").apply { if (!exists()) mkdirs() }

    fun list(context: Context): List<Entry> {
        val stored = readIndex(context)
        return listOf(builtinEntry(context)) + stored
    }

    fun builtinEntry(context: Context): Entry {
        val anim = runCatching { context.assets.open(BUILTIN_ASSET).use { DotAnimation.decode(it) } }.getOrNull()
        return Entry(
            id = BUILTIN_ID,
            name = anim?.name ?: "内置演示",
            fps = anim?.fps ?: 12,
            frameCount = anim?.frameCount ?: 0,
            builtin = true,
            path = "asset://$BUILTIN_ASSET"
        )
    }

    fun load(context: Context, entry: Entry): DotAnimation {
        return if (entry.builtin) {
            context.assets.open(BUILTIN_ASSET).use { DotAnimation.decode(it) }
        } else {
            File(entry.path).inputStream().use { DotAnimation.decode(it) }
        }
    }

    fun save(context: Context, anim: DotAnimation): Entry {
        val id = "anim-${System.currentTimeMillis()}"
        val file = File(dir(context), "$id.bsa")
        file.outputStream().use { DotAnimation.encode(anim, it) }
        val entry = Entry(
            id = id,
            name = anim.name,
            fps = anim.fps,
            frameCount = anim.frameCount,
            builtin = false,
            path = file.absolutePath
        )
        val stored = readIndex(context)
        writeIndex(context, stored + entry)
        return entry
    }

    fun delete(context: Context, entry: Entry) {
        if (entry.builtin) return
        runCatching { File(entry.path).delete() }
        writeIndex(context, readIndex(context).filterNot { it.id == entry.id })
    }

    fun rename(context: Context, entry: Entry, newName: String) {
        if (entry.builtin) return
        val updated = readIndex(context).map { if (it.id == entry.id) it.copy(name = newName) else it }
        writeIndex(context, updated)
    }

    private fun readIndex(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INDEX, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                if (path.isEmpty() || !File(path).exists()) return@mapNotNull null
                Entry(
                    id = o.optString("id"),
                    name = o.optString("name", "动画"),
                    fps = o.optInt("fps", 12),
                    frameCount = o.optInt("frameCount", 0),
                    builtin = false,
                    path = path
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.filterNot { it.builtin }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("fps", e.fps)
                put("frameCount", e.frameCount)
                put("path", e.path)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INDEX, arr.toString())
            .apply()
    }
}
