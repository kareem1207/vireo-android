package com.vireo.thermal

import android.util.Log
import com.vireo.llm.TokenPacer
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Drives the generation loop from the current [ThermalTier]:
 *  - NORMAL : no delay
 *  - ECO    : sleep [ecoDelayMs] per token
 *  - PAUSE  : block (keeping the model's KV cache) until the tier clears,
 *             the app leaves the foreground, or generation is cancelled
 *
 * Runs on the native generation thread, so blocking here blocks generation —
 * which is the point.
 */
class ThermalPacer(
    private val governor: ThermalGovernor,
    private val isForeground: () -> Boolean,
    private val isCancelled: () -> Boolean,
    private val ecoDelayMs: Long = 30L,
    /** Surfaced to the UI ("cooling down…" banner, status chip). */
    val effectiveTier: MutableStateFlow<ThermalTier> = MutableStateFlow(ThermalTier.NORMAL),
) : TokenPacer {

    override fun beforeToken(): Boolean {
        while (!isCancelled()) {
            val snap = governor.snapshot.value
            val paused = !isForeground() || snap.tier == ThermalTier.PAUSE

            when {
                paused -> {
                    if (effectiveTier.value != ThermalTier.PAUSE) {
                        effectiveTier.value = ThermalTier.PAUSE
                        Log.i(TAG, "cooling pause (${if (!isForeground()) "background" else snap.reason})")
                    }
                    Thread.sleep(PAUSE_POLL_MS)
                }
                snap.tier == ThermalTier.ECO -> {
                    if (effectiveTier.value != ThermalTier.ECO) {
                        effectiveTier.value = ThermalTier.ECO
                        Log.i(TAG, "eco throttle (${snap.reason})")
                    }
                    Thread.sleep(ecoDelayMs)
                    return true
                }
                else -> {
                    if (effectiveTier.value != ThermalTier.NORMAL) effectiveTier.value = ThermalTier.NORMAL
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val TAG = "Vireo"
        private const val PAUSE_POLL_MS = 300L
    }
}
