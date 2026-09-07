package com.vireo.models

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class CatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val params: String,
    val quant: String,
    val role: String,          // "chat" | "embedding"
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val minRamMb: Int,
    val contextMax: Int,
    val license: String,
    val notes: String,
) {
    val fileName get() = "$id.gguf"
    val sizeMb get() = sizeBytes / (1024.0 * 1024.0)
}

/** Reads the bundled assets/model_catalog.json. (Remote refresh is a later add.) */
object ModelCatalog {

    fun load(context: Context): List<CatalogModel> = runCatching {
        val raw = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).getJSONArray("models")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CatalogModel(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        family = o.getString("family"),
                        params = o.getString("params"),
                        quant = o.getString("quant"),
                        role = o.getString("role"),
                        sizeBytes = o.getLong("sizeBytes"),
                        sha256 = o.getString("sha256").lowercase(),
                        url = o.getString("url"),
                        minRamMb = o.getInt("minRamMb"),
                        contextMax = o.getInt("contextMax"),
                        license = o.getString("license"),
                        notes = o.optString("notes"),
                    )
                )
            }
        }
    }.getOrElse {
        Log.e("Vireo", "model catalog load failed", it)
        emptyList()
    }
}
