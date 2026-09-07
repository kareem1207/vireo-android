package com.vireo.models

import android.content.Context
import java.io.File

/**
 * M2 stopgap model registry: whatever `.gguf` files are already in the app's
 * external files/models dir (put there via `adb push` or, from M4, the in-app
 * downloader). Remembers the active choice in SharedPreferences.
 */
class ModelStore(context: Context) {

    val dir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("vireo_models", Context.MODE_PRIVATE)

    fun available(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun active(): File? {
        val saved = prefs.getString("active", null)?.let { File(it) }
        if (saved != null && saved.exists()) return saved
        return available().firstOrNull()?.also { setActive(it) }
    }

    fun setActive(file: File) = prefs.edit().putString("active", file.absolutePath).apply()

    fun label(file: File?): String = file?.name
        ?.removeSuffix(".gguf")?.removeSuffix(".GGUF")
        ?: "(no model)"
}
