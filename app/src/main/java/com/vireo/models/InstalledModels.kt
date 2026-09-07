package com.vireo.models

import android.content.Context
import java.io.File

data class ActiveModel(val id: String, val path: String, val label: String, val minRamMb: Int)

/**
 * Source of truth for which models are on disk and which one the chat uses.
 * Files live in filesDir/models/<id>.gguf (internal storage → mmap-safe).
 * Metadata is just SharedPreferences; the files themselves are authoritative.
 */
class InstalledModels(context: Context) {

    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("vireo_installed", Context.MODE_PRIVATE)

    fun fileFor(id: String) = File(dir, "$id.gguf")

    fun isInstalled(id: String): Boolean = fileFor(id).let { it.isFile && it.length() > 0 }

    fun installedIds(): Set<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") } ?: emptyArray())
            .map { it.name.removeSuffix(".gguf") }.toSet()

    fun delete(id: String) {
        fileFor(id).delete()
        if (activeId() == id) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)?.takeIf { isInstalled(it) }

    fun setActive(id: String) {
        require(isInstalled(id)) { "model $id is not installed" }
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Resolve the active chat model, falling back to any installed chat model. */
    fun active(catalog: List<CatalogModel>): ActiveModel? {
        val chatCatalog = catalog.filter { it.role == "chat" }
        val id = activeId()
            ?: chatCatalog.firstOrNull { isInstalled(it.id) }?.id
            ?: return null
        val cm = catalog.firstOrNull { it.id == id }
        return ActiveModel(
            id = id,
            path = fileFor(id).absolutePath,
            label = cm?.name ?: id,
            minRamMb = cm?.minRamMb ?: 0,
        )
    }

    /** Path to an installed embedding model, or null. */
    fun embeddingPath(catalog: List<CatalogModel>): String? =
        catalog.firstOrNull { it.role == "embedding" && isInstalled(it.id) }
            ?.let { fileFor(it.id).absolutePath }

    companion object { private const val KEY_ACTIVE = "active" }
}
