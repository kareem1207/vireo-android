package com.vireo.thermal

import android.util.Log
import java.io.File

/** Appends one CSV row per sample to files/telemetry/run.csv (for the Stage-I eval graphs). */
class TelemetryLogger(filesDir: File) {

    private val file = File(filesDir, "telemetry/run.csv").also { it.parentFile?.mkdirs() }

    init {
        if (!file.exists()) {
            runCatching { file.writeText("ts,event,gen_tok_s,status,headroom,battery_c,threads,tier,reason\n") }
        }
    }

    fun row(event: String, genTokPerSec: Float, s: ThermalSnapshot, threads: Int) {
        runCatching {
            file.appendText(
                buildString {
                    append(System.currentTimeMillis()); append(',')
                    append(event); append(',')
                    append("%.2f".format(genTokPerSec)); append(',')
                    append(s.statusName); append(',')
                    append(if (s.headroom.isNaN()) "" else "%.3f".format(s.headroom)); append(',')
                    append(if (s.batteryC.isNaN()) "" else "%.1f".format(s.batteryC)); append(',')
                    append(threads); append(',')
                    append(s.tier); append(',')
                    append(s.reason.replace(',', ' '))
                    append('\n')
                }
            )
        }.onFailure { Log.w("Vireo", "telemetry write failed", it) }
    }

    val path: String get() = file.absolutePath
    fun readCsv(): String = runCatching { file.readText() }.getOrDefault("")

    private val benchFile = File(file.parentFile, "bench.csv").also {
        if (!it.exists()) runCatching { it.writeText("ts,model,gen_tok_s,prompt_tok_s,rounds,peak_battery_c,max_status\n") }
    }

    fun benchRow(model: String, genTokPerSec: Float, promptTokPerSec: Float, rounds: Int,
                 peakBatteryC: Float, maxStatus: String) {
        runCatching {
            benchFile.appendText(
                "${System.currentTimeMillis()},$model,${"%.2f".format(genTokPerSec)}," +
                    "${"%.2f".format(promptTokPerSec)},$rounds," +
                    "${if (peakBatteryC.isNaN()) "" else "%.1f".format(peakBatteryC)},$maxStatus\n"
            )
        }
    }

    fun benchCsv(): String = runCatching { benchFile.readText() }.getOrDefault("")
}
