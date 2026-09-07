package com.vireo.llm

/**
 * Called on the native generation thread just before each generated token is
 * surfaced. Implementations may block (to slow generation down) or spin (to
 * pause it). Returning false aborts the current generation.
 */
fun interface TokenPacer {
    fun beforeToken(): Boolean
}

/** No pacing — full speed. */
val NoPacer = TokenPacer { true }
