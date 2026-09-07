package com.vireo.diag

import com.vireo.llm.ChatMsg
import com.vireo.llm.GenEvent
import com.vireo.llm.GenParams
import com.vireo.llm.LlmEngine
import com.vireo.thermal.TelemetryLogger
import com.vireo.thermal.ThermalGovernor

data class BenchResult(
    val genTokPerSec: Float,
    val promptTokPerSec: Float,
    val peakBatteryC: Float,
    val maxStatus: String,
)

object Benchmark {

    private const val PROMPT = "Explain how a CPU cache reduces memory latency, in four sentences."

    /** Greedy 96-token generation, [rounds] times; averages tok/s and records the run. */
    suspend fun run(
        engine: LlmEngine,
        governor: ThermalGovernor,
        telemetry: TelemetryLogger,
        modelLabel: String,
        rounds: Int = 3,
    ): BenchResult {
        var gen = 0f
        var prompt = 0f
        var peakBattery = Float.NaN
        var maxStatus = "none"

        repeat(rounds) {
            engine.generate(
                listOf(ChatMsg("user", PROMPT)),
                GenParams(maxTokens = 96, temp = 0f),
            ).collect { ev ->
                if (ev is GenEvent.Done) { gen += ev.tokPerSec; prompt += ev.promptTokPerSec }
            }
            val s = governor.snapshot.value
            if (!s.batteryC.isNaN() && (peakBattery.isNaN() || s.batteryC > peakBattery)) peakBattery = s.batteryC
            if (statusRank(s.statusName) > statusRank(maxStatus)) maxStatus = s.statusName
        }

        val res = BenchResult(gen / rounds, prompt / rounds, peakBattery, maxStatus)
        telemetry.benchRow(modelLabel, res.genTokPerSec, res.promptTokPerSec, rounds, peakBattery, maxStatus)
        return res
    }

    private fun statusRank(s: String) = when (s) {
        "none" -> 0; "light" -> 1; "moderate" -> 2; "severe" -> 3
        "critical" -> 4; "emergency" -> 5; "shutdown" -> 6; else -> 0
    }
}
