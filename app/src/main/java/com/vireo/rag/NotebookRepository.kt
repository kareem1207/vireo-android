package com.vireo.rag

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Notebook(val id: String, val name: String, val createdAt: Long)
data class DocMeta(val docId: String, val fileName: String, val pages: Int, val chunks: Int)

/** File-backed notebook store under filesDir/notebooks/. */
class NotebookRepository(filesDir: File) {

    private val root = File(filesDir, "notebooks").apply { mkdirs() }
    private val indexFile = File(root, "notebooks.json")

    fun list(): List<Notebook> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Notebook(o.getString("id"), o.getString("name"), o.getLong("createdAt")))
            }
        }.sortedByDescending { it.createdAt }
    }.getOrElse { Log.e("Vireo", "notebook list failed", it); emptyList() }

    private fun saveIndex(items: List<Notebook>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("createdAt", it.createdAt)
            })
        }
        indexFile.writeText(arr.toString())
    }

    fun create(name: String): Notebook {
        val nb = Notebook(UUID.randomUUID().toString().take(12), name.trim().ifEmpty { "Untitled" }, System.currentTimeMillis())
        saveIndex(list() + nb)
        return nb
    }

    fun rename(id: String, name: String) = saveIndex(list().map { if (it.id == id) it.copy(name = name) else it })

    fun delete(id: String) {
        saveIndex(list().filterNot { it.id == id })
        vectorStore(id).deleteFiles()
        docsFile(id).delete()
    }

    private fun docsFile(id: String) = File(root, "$id.docs.json")

    fun docs(id: String): List<DocMeta> = runCatching {
        if (!docsFile(id).exists()) return emptyList()
        val arr = JSONArray(docsFile(id).readText())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(DocMeta(o.getString("docId"), o.getString("fileName"), o.getInt("pages"), o.getInt("chunks")))
            }
        }
    }.getOrElse { emptyList() }

    fun putDoc(id: String, doc: DocMeta) {
        val updated = docs(id).filterNot { it.docId == doc.docId } + doc
        val arr = JSONArray()
        updated.forEach {
            arr.put(JSONObject().apply {
                put("docId", it.docId); put("fileName", it.fileName)
                put("pages", it.pages); put("chunks", it.chunks)
            })
        }
        docsFile(id).writeText(arr.toString())
    }

    fun removeDoc(id: String, docId: String) {
        val store = vectorStore(id)
        store.ensureLoaded(); store.removeDoc(docId); store.persist()
        val arr = JSONArray()
        docs(id).filterNot { it.docId == docId }.forEach {
            arr.put(JSONObject().apply {
                put("docId", it.docId); put("fileName", it.fileName)
                put("pages", it.pages); put("chunks", it.chunks)
            })
        }
        docsFile(id).writeText(arr.toString())
    }

    fun vectorStore(id: String) = VectorStore(root, id)
}
