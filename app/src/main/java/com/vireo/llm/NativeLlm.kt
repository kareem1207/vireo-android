package com.vireo.llm

/**
 * Thin JNI surface to llama.cpp (native lib: libvireo_llm.so).
 * All native calls are blocking; callers must run them off the main thread
 * (see [LlmEngine]).
 */
object NativeLlm {

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("vireo_llm")
                loaded = true
            }
        }
    }

    external fun nativePing(): String

    /** @return opaque handle, or 0 on failure. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        callback: GenerationCallback,
    )

    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)

    fun ping(): String { ensureLoaded(); return nativePing() }
}

/** Native calls back into this per generated token and once at the end. */
interface GenerationCallback {
    fun onToken(piece: String)
    fun onDone(tokPerSec: Float, nTokens: Int, promptEvalTokPerSec: Float)
}
