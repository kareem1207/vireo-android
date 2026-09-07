package com.vireo.rag

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class Chunk(
    val docId: String,
    val fileName: String,
    val page: Int,
    val charStart: Int,
    val text: String,
)

/**
 * In-memory vector index for one notebook, persisted as two sibling files:
 *   <id>.chunks.json  — chunk metadata + text
 *   <id>.vecs.bin     — int32 count, int32 dim, then count*dim big-endian float32
 * Vectors are stored L2-normalised, so cosine similarity is a plain dot product.
 * Brute-force search is fine for the low-thousands of chunks a phone notebook holds.
 */
class VectorStore(dir: File, notebookId: String) {

    private val chunksFile = File(dir, "$notebookId.chunks.json")
    private val vecsFile = File(dir, "$notebookId.vecs.bin")

    private val chunks = mutableListOf<Chunk>()
    private val vecs = mutableListOf<FloatArray>()
    var dim = 0
        private set
    private var loaded = false

    fun size() = chunks.size

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!chunksFile.exists() || !vecsFile.exists()) return
        runCatching {
            val arr = JSONArray(chunksFile.readText())
            DataInputStream(vecsFile.inputStream().buffered()).use { din ->
                val count = din.readInt()
                dim = din.readInt()
                for (i in 0 until count) {
                    val o = arr.getJSONObject(i)
                    chunks += Chunk(
                        docId = o.getString("docId"),
                        fileName = o.getString("fileName"),
                        page = o.getInt("page"),
                        charStart = o.getInt("charStart"),
                        text = o.getString("text"),
                    )
                    val v = FloatArray(dim) { din.readFloat() }
                    vecs += v
                }
            }
            Log.i("Vireo", "vector store loaded: ${chunks.size} chunks, dim=$dim")
        }.onFailure {
            Log.e("Vireo", "vector store load failed", it)
            chunks.clear(); vecs.clear(); dim = 0
        }
    }

    @Synchronized
    fun add(chunk: Chunk, vec: FloatArray) {
        if (dim == 0) dim = vec.size
        require(vec.size == dim) { "embedding dim mismatch ${vec.size} != $dim" }
        chunks += chunk
        vecs += vec
    }

    @Synchronized
    fun removeDoc(docId: String) {
        for (i in chunks.indices.reversed()) {
            if (chunks[i].docId == docId) { chunks.removeAt(i); vecs.removeAt(i) }
        }
    }

    @Synchronized
    fun search(query: FloatArray, k: Int, minScore: Float): List<Pair<Chunk, Float>> {
        ensureLoaded()
        if (chunks.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Chunk, Float>>(chunks.size)
        for (i in chunks.indices) {
            var dot = 0f
            val v = vecs[i]
            for (j in query.indices) dot += query[j] * v[j]
            if (dot >= minScore) scored += chunks[i] to dot
        }
        scored.sortByDescending { it.second }
        // drop near-duplicate neighbours (same doc, adjacent offsets)
        val picked = mutableListOf<Pair<Chunk, Float>>()
        for (cand in scored) {
            if (picked.size >= k) break
            val dup = picked.any {
                it.first.docId == cand.first.docId &&
                    it.first.page == cand.first.page &&
                    kotlin.math.abs(it.first.charStart - cand.first.charStart) < 400
            }
            if (!dup) picked += cand
        }
        return picked
    }

    @Synchronized
    fun persist() {
        runCatching {
            val arr = JSONArray()
            chunks.forEach {
                arr.put(JSONObject().apply {
                    put("docId", it.docId); put("fileName", it.fileName)
                    put("page", it.page); put("charStart", it.charStart); put("text", it.text)
                })
            }
            chunksFile.writeText(arr.toString())
            DataOutputStream(vecsFile.outputStream().buffered()).use { dout ->
                dout.writeInt(chunks.size)
                dout.writeInt(dim)
                vecs.forEach { v -> v.forEach { dout.writeFloat(it) } }
            }
        }.onFailure { Log.e("Vireo", "vector store persist failed", it) }
    }

    @Synchronized
    fun deleteFiles() { chunksFile.delete(); vecsFile.delete() }
}
