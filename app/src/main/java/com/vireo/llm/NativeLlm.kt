package com.vireo.llm

/** Thin JNI surface to llama.cpp (native lib: libvireo_llm.so). All calls blocking. */
object NativeLlm {

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) { System.loadLibrary("vireo_llm"); loaded = true }
        }
    }

    external fun nativePing(): String

    /** @return opaque handle, or 0 on failure. embeddings != 0 opens the context in embedding mode. */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int, embeddings: Int): Long

    /** Mean-pooled, L2-normalised embedding for [text]; null on failure. Handle must be embedding-mode. */
    external fun nativeEmbed(handle: Long, text: String): FloatArray?
    external fun nativeEmbedDim(handle: Long): Int

    /** roles[i]/contents[i] form the chat transcript; native applies the model's template. */
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
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

interface GenerationCallback {
    fun onToken(piece: String)
    fun onDone(tokPerSec: Float, nTokens: Int, promptEvalTokPerSec: Float)
}
