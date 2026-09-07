package com.vireo.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Models", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${s.freeStorageMb} MB free · ${s.totalRamMb} MB RAM",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(s.rows, key = { it.model.id }) { row -> ModelCard(row, vm) }
        }
    }
}

@Composable
private fun ModelCard(row: ModelRow, vm: ModelsViewModel) {
    val m = row.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                if (row.active) Text("● active", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${m.params} · ${m.quant} · ${"%.0f".format(m.sizeMb)} MB · ${m.role} · ${m.license}",
                style = MaterialTheme.typography.labelSmall,
            )
            if (m.notes.isNotBlank()) {
                Text(m.notes, style = MaterialTheme.typography.bodySmall)
            }
            if (row.ramWarn) {
                Text(
                    "⚠ needs ~${m.minRamMb} MB RAM — may fail to load on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val d = row.download
            when {
                d?.phase == DownloadPhase.FAILED -> {
                    Text("Download failed: ${d.error}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.dismissError(m.id); vm.download(m) }) { Text("Retry") }
                        TextButton(onClick = { vm.dismissError(m.id) }) { Text("Dismiss") }
                    }
                }

                d != null && (d.phase == DownloadPhase.RUNNING || d.phase == DownloadPhase.VERIFYING) -> {
                    LinearProgressIndicator(
                        progress = { if (d.phase == DownloadPhase.VERIFYING) 1f else d.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val line = if (d.phase == DownloadPhase.VERIFYING) "verifying checksum…"
                    else "%.0f / %.0f MB · %.1f MB/s".format(
                        d.bytes / 1048576.0, d.total / 1048576.0, d.bytesPerSec / 1048576.0
                    )
                    Text(line, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { vm.cancelDownload(m.id) }) { Text("Cancel") }
                }

                row.installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!row.active && m.role == "chat") {
                        OutlinedButton(onClick = { vm.activate(m.id) }) { Text("Activate") }
                    }
                    TextButton(onClick = { vm.delete(m.id) }) { Text("Delete") }
                }

                else -> OutlinedButton(onClick = { vm.download(m) }) { Text("Download") }
            }
        }
    }
}
