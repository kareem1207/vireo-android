package com.vireo.diag

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vireo.llm.LlmEngine
import com.vireo.rag.Embedder
import com.vireo.thermal.TelemetryLogger
import com.vireo.thermal.ThermalGovernor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    engine: LlmEngine,
    embedder: Embedder,
    governor: ThermalGovernor,
    telemetry: TelemetryLogger,
    activeModelLabel: String,
    installedIds: Set<String>,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val thermal by governor.snapshot.collectAsState()
    var benchLine by remember { mutableStateOf("(not run)") }
    var benching by remember { mutableStateOf(false) }

    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val filesDir = ctx.filesDir

    val exportRun = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(telemetry.readCsv().toByteArray()) }
    }
    val exportBench = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(telemetry.benchCsv().toByteArray()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Device") {
                Line("Total RAM", "%.1f GB".format(mi.totalMem / 1e9))
                Line("Available RAM", "%.1f GB".format(mi.availMem / 1e9))
                Line("Low memory", mi.lowMemory.toString())
                Line("Free app storage", "%d MB".format(filesDir.usableSpace / (1024 * 1024)))
            }

            Section("Models") {
                Line("Active chat model", activeModelLabel)
                Line("Chat engine loaded", engine.isLoaded.toString())
                Line("Embedder loaded", if (embedder.isLoaded) "yes (dim ${embedder.dim})" else "no")
                Line("Installed", installedIds.joinToString(", ").ifEmpty { "none" })
            }

            Section("Thermal (live)") {
                Line("Status", thermal.statusName)
                Line("Headroom", if (thermal.headroom.isNaN()) "n/a" else "%.2f".format(thermal.headroom))
                Line("Battery", if (thermal.batteryC.isNaN()) "n/a" else "%.1f °C".format(thermal.batteryC))
                Line("Governor tier", "${thermal.tier} (${thermal.reason})")
            }

            Section("Benchmark (active model)") {
                Text(benchLine, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = engine.isLoaded && !benching,
                    onClick = {
                        benching = true
                        benchLine = "running 3 rounds…"
                        scope.launch {
                            val r = runCatching {
                                Benchmark.run(engine, governor, telemetry, activeModelLabel)
                            }.getOrNull()
                            benchLine = r?.let {
                                "gen %.1f tok/s · prompt-eval %.1f tok/s · peak %s".format(
                                    it.genTokPerSec, it.promptTokPerSec,
                                    if (it.peakBatteryC.isNaN()) "n/a" else "%.1f°C".format(it.peakBatteryC),
                                )
                            } ?: "benchmark failed"
                            benching = false
                        }
                    },
                ) { Text(if (benching) "Benchmarking…" else "Run benchmark") }
            }

            Section("Telemetry") {
                OutlinedButton(onClick = { exportRun.launch("vireo_thermal_run.csv") }) { Text("Export run.csv") }
                OutlinedButton(onClick = { exportBench.launch("vireo_bench.csv") }) { Text("Export bench.csv") }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            body()
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    Text("$k: $v", style = MaterialTheme.typography.bodySmall)
}
