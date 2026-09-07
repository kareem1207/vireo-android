package com.vireo.llm

/**
 * Thin JNI surface to llama.cpp (native lib: libvireo_llm.so).
 *
 * M1 step 1: [nativePing] only — proves the toolchain links and the ggml/llama
 * runtime initialises on-device. Model load / generate / embed follow next.
 */
object NativeLlm {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("vireo_llm")
                loaded = true
            }
        }
    }

    /** Returns "llm-ok | <llama system info>" from native. */
    external fun nativePing(): String

    fun ping(): String {
        ensureLoaded()
        return nativePing()
    }
}
