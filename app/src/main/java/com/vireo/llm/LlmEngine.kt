package com.vireo.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executors

/** Parameters for one generation call. */
data class GenParams(
    val maxTokens: Int = 512,
    val temp: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
)

sealed interface GenEvent {
    data class Token(val piece: String) : GenEvent
    data class Done(val tokPerSec: Float, val nTokens: Int, val promptTokPerSec: Float) : GenEvent
}

/**
 * Owns a single loaded model + context. All native work runs on one dedicated
 * thread so llama.cpp is never touched concurrently.
 */
class LlmEngine {

    private val worker: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vireo-llm") }.asCoroutineDispatcher()

    @Volatile private var handle: Long = 0L
    @Volatile var loadedPath: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    /** Blocking; call from [worker] context. */
    private fun loadBlocking(path: String, nCtx: Int, nThreads: Int, nBatch: Int) {
        NativeLlm.ensureLoaded()
        if (handle != 0L) { NativeLlm.nativeFree(handle); handle = 0L; loadedPath = null }
        val h = NativeLlm.nativeLoadModel(path, nCtx, nThreads, nBatch)
        check(h != 0L) { "nativeLoadModel failed for $path" }
        handle = h
        loadedPath = path
        Log.i(TAG, "loaded $path")
    }

    suspend fun load(path: String, nCtx: Int = 2048, nThreads: Int = 2, nBatch: Int = 128) =
        kotlinx.coroutines.withContext(worker) { loadBlocking(path, nCtx, nThreads, nBatch) }

    fun generate(prompt: String, params: GenParams = GenParams()): Flow<GenEvent> = callbackFlow {
        val h = handle
        require(h != 0L) { "no model loaded" }
        val cb = object : GenerationCallback {
            override fun onToken(piece: String) { trySend(GenEvent.Token(piece)) }
            override fun onDone(tokPerSec: Float, nTokens: Int, promptEvalTokPerSec: Float) {
                trySend(GenEvent.Done(tokPerSec, nTokens, promptEvalTokPerSec))
                close()
            }
        }
        NativeLlm.nativeGenerate(
            h, prompt, params.maxTokens, params.temp, params.topP, params.topK, params.minP, params.seed, cb
        )
        awaitClose { NativeLlm.nativeCancel(h) }
    }.flowOn(worker)

    fun cancel() { if (handle != 0L) NativeLlm.nativeCancel(handle) }

    suspend fun free() = kotlinx.coroutines.withContext(worker) {
        if (handle != 0L) { NativeLlm.nativeFree(handle); handle = 0L; loadedPath = null }
    }

    companion object { private const val TAG = "Vireo" }
}
