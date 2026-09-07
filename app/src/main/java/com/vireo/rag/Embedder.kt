package com.vireo.rag

import android.util.Log
import com.vireo.llm.NativeLlm
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Owns a second llama.cpp context, loaded in embedding mode (EmbeddingGemma).
 * Separate worker thread from [com.vireo.llm.LlmEngine] so chat and embedding
 * never touch the native layer concurrently on the same thread.
 */
class Embedder {

    private val worker =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vireo-embed") }.asCoroutineDispatcher()

    @Volatile private var handle = 0L
    var dim = 0
        private set
    val isLoaded get() = handle != 0L

    suspend fun load(path: String, nThreads: Int = 2) = withContext(worker) {
        NativeLlm.ensureLoaded()
        if (handle != 0L) { NativeLlm.nativeFree(handle); handle = 0L }
        val h = NativeLlm.nativeLoadModel(path, /*nCtx=*/1024, nThreads, /*nBatch=*/1024, /*embeddings=*/1)
        check(h != 0L) { "embedding model load failed: $path" }
        handle = h
        dim = NativeLlm.nativeEmbedDim(h)
        Log.i("Vireo", "embedder ready, dim=$dim")
    }

    suspend fun free() = withContext(worker) {
        if (handle != 0L) { NativeLlm.nativeFree(handle); handle = 0L; dim = 0 }
    }

    private suspend fun embed(text: String): FloatArray = withContext(worker) {
        val h = handle
        require(h != 0L) { "embedder not loaded" }
        NativeLlm.nativeEmbed(h, text) ?: error("nativeEmbed returned null")
    }

    // EmbeddingGemma prompt conventions
    suspend fun embedDocument(text: String): FloatArray = embed("title: none | text: $text")
    suspend fun embedQuery(text: String): FloatArray = embed("task: search result | query: $text")
}
