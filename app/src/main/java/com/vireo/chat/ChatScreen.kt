package com.vireo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text) {
        if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vireo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${s.modelName} · ${s.statusLine}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = { TextButton(onClick = vm::clear) { Text("Clear") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                itemsIndexed(s.messages) { _, m -> Bubble(m) }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask something…") },
                    enabled = s.modelReady,
                    maxLines = 4,
                    keyboardActions = KeyboardActions(onSend = {
                        vm.send(input); input = ""
                    }),
                )
                if (s.busy) {
                    Button(onClick = vm::stop) { Text("Stop") }
                } else {
                    Button(
                        onClick = { vm.send(input); input = "" },
                        enabled = s.modelReady && input.isNotBlank(),
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: UiMessage) {
    val isUser = m.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                m.text.ifEmpty { if (m.streaming) "…" else "" },
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (m.stats != null) {
                Text(m.stats, color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
