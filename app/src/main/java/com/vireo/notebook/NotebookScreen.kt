package com.vireo.notebook

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(vm: NotebookViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    var showSources by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "document"
            vm.importDocument(uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(12.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.embedModelMissing) {
                Text(
                    "Notebooks need the embedding model. Open the ⋮ menu → Models and download “EmbeddingGemma 300M”.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) },
                enabled = s.embedReady && !s.ingesting,
            ) { Text(if (s.ingesting) "Importing…" else "Import PDF / text") }

            if (s.ingesting && s.ingestTotal > 0) {
                LinearProgressIndicator(
                    progress = { s.ingestDone.toFloat() / s.ingestTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("embedding ${s.ingestDone}/${s.ingestTotal} chunks", style = MaterialTheme.typography.labelSmall)
            }

            if (s.docs.isNotEmpty()) {
                Text("Documents", style = MaterialTheme.typography.labelMedium)
                s.docs.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${d.fileName}  ·  ${d.pages}p · ${d.chunks} ch",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.removeDoc(d.docId) }) { Text("×") }
                    }
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = s.question,
                onValueChange = vm::setQuestion,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask about your notes…") },
                enabled = s.embedReady && s.docs.isNotEmpty(),
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.answering) Button(onClick = vm::stop) { Text("Stop") }
                else Button(
                    onClick = vm::ask,
                    enabled = s.embedReady && s.docs.isNotEmpty() && s.question.isNotBlank(),
                ) { Text("Ask") }
            }

            if (s.answer.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        s.answer,
                        Modifier.padding(12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (s.citations.isNotEmpty()) {
                TextButton(onClick = { showSources = !showSources }) {
                    Text(if (showSources) "Hide sources" else "Show ${s.citations.size} sources")
                }
                if (showSources) {
                    s.citations.forEach { c ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("[${c.index}] ${c.fileName} · p.${c.page}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(c.snippet + "…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
