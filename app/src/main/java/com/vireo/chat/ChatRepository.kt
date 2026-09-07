package com.vireo.chat

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Minimal JSON-file chat history. Room arrives in M5 with notebooks. */
class ChatRepository(filesDir: File) {

    private val file = File(filesDir, "chat_history.json")

    fun load(): List<UiMessage> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val out = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    UiMessage(
                        role = o.getString("role"),
                        text = o.getString("text"),
                        stats = o.optString("stats").ifEmpty { null },
                    )
                )
            }
        }
        // a crash / kill mid-reply can leave a trailing user turn with no answer
        if (out.lastOrNull()?.role == "user") out.dropLast(1) else out
    }.getOrElse {
        Log.w("Vireo", "chat history load failed", it); emptyList()
    }

    fun save(messages: List<UiMessage>) {
        runCatching {
            val arr = JSONArray()
            messages.filterNot { it.streaming }.forEach {
                arr.put(JSONObject().apply {
                    put("role", it.role); put("text", it.text)
                    if (it.stats != null) put("stats", it.stats)
                })
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w("Vireo", "chat history save failed", it) }
    }
}
