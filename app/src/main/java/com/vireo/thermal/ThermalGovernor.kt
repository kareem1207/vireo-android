package com.vireo.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class ThermalTier { NORMAL, ECO, PAUSE }

data class ThermalSnapshot(
    val statusName: String = "unknown",
    val status: Int = -1,
    val headroom: Float = Float.NaN,
    val batteryC: Float = Float.NaN,
    val tier: ThermalTier = ThermalTier.NORMAL,
    val reason: String = "starting",
)

/**
 * Watches the OS thermal signals and boils them down to a [ThermalTier] the
 * generation loop can act on:
 *  - NORMAL : run at full speed
 *  - ECO    : insert a per-token delay (see [ThermalPacer])
 *  - PAUSE  : stop feeding tokens until the device cools
 *
 * Signals: [PowerManager.getCurrentThermalStatus] + a thermal-status listener
 * (API 29+), [PowerManager.getThermalHeadroom] 10 s forecast (API 30+, may be
 * NaN on some OEM builds — then ignored), and battery temperature from the
 * sticky ACTION_BATTERY_CHANGED broadcast.
 */
class ThermalGovernor(context: Context) {

    private val app = context.applicationContext
    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _snapshot = MutableStateFlow(ThermalSnapshot())
    val snapshot: StateFlow<ThermalSnapshot> = _snapshot.asStateFlow()

    /** User "Eco Mode" — forces at least ECO regardless of temperature. */
    @Volatile var ecoMode: Boolean = false
        set(v) { field = v; recompute() }

    /** Debug-only override so the three paths can be demoed on a cool device. */
    @Volatile var debugOverride: ThermalTier? = null
        set(v) { field = v; recompute() }

    private var statusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var poller: ScheduledExecutorService? = null
    @Volatile private var lastStatus: Int = 0

    fun start() {
        if (poller != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lastStatus = runCatching { pm.currentThermalStatus }.getOrDefault(0)
            val l = PowerManager.OnThermalStatusChangedListener { st ->
                lastStatus = st
                Log.i(TAG, "thermal status -> ${statusName(st)}")
                recompute()
            }
            runCatching { pm.addThermalStatusListener(l) }
            statusListener = l
        }
        poller = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vireo-thermal") }.also {
            it.scheduleWithFixedDelay({ recompute() }, 0, POLL_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        statusListener?.let { runCatching { pm.removeThermalStatusListener(it) } }
        statusListener = null
        poller?.shutdownNow()
        poller = null
    }

    private fun readHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        return runCatching { pm.getThermalHeadroom(FORECAST_SECONDS) }
            .getOrDefault(Float.NaN)
            .let { if (it.isNaN() || it < 0f || it.isInfinite()) Float.NaN else it }
    }

    private fun readBatteryC(): Float = runCatching {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }.getOrDefault(Float.NaN)

    private fun recompute() {
        val status = lastStatus
        val headroom = readHeadroom()
        val batteryC = readBatteryC()
        val (tier, reason) = classify(status, headroom, batteryC, ecoMode, debugOverride)
        val snap = ThermalSnapshot(statusName(status), status, headroom, batteryC, tier, reason)
        if (snap != _snapshot.value) _snapshot.value = snap
    }

    companion object {
        private const val TAG = "Vireo"
        const val FORECAST_SECONDS = 10
        const val POLL_SECONDS = 2L

        // Thresholds — deliberately conservative for a mid-range MTK SoC; tune in eval.
        const val HEADROOM_ECO = 0.75f
        const val HEADROOM_PAUSE = 0.92f
        const val BATTERY_ECO_C = 40f
        const val BATTERY_PAUSE_C = 44f

        /** Pure decision function — unit-tested in ThermalPolicyTest. */
        fun classify(
            status: Int,
            headroom: Float,
            batteryC: Float,
            eco: Boolean,
            override: ThermalTier?,
        ): Pair<ThermalTier, String> {
            override?.let { return it to "debug override" }

            // PAUSE conditions
            if (status >= STATUS_SEVERE) return ThermalTier.PAUSE to "thermal ${statusName(status)}"
            if (!headroom.isNaN() && headroom >= HEADROOM_PAUSE)
                return ThermalTier.PAUSE to "headroom %.2f".format(headroom)
            if (!batteryC.isNaN() && batteryC >= BATTERY_PAUSE_C)
                return ThermalTier.PAUSE to "battery %.1f°C".format(batteryC)

            // ECO conditions
            if (status == STATUS_MODERATE) return ThermalTier.ECO to "thermal moderate"
            if (!headroom.isNaN() && headroom >= HEADROOM_ECO)
                return ThermalTier.ECO to "headroom %.2f".format(headroom)
            if (!batteryC.isNaN() && batteryC >= BATTERY_ECO_C)
                return ThermalTier.ECO to "battery %.1f°C".format(batteryC)
            if (eco) return ThermalTier.ECO to "eco mode"

            return ThermalTier.NORMAL to "ok"
        }

        // PowerManager.THERMAL_STATUS_* mirrored so the pure fn needs no framework import.
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3

        fun statusName(s: Int): String = when (s) {
            STATUS_NONE -> "none"
            STATUS_LIGHT -> "light"
            STATUS_MODERATE -> "moderate"
            STATUS_SEVERE -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> "unknown"
        }
    }
}
