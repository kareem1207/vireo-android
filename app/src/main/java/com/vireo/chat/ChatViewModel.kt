package com.vireo.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vireo.llm.ChatMsg
import com.vireo.llm.GenEvent
import com.vireo.llm.GenParams
import com.vireo.llm.LlmEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiMessage(
    val role: String,          // "user" | "assistant"
    val text: String,
    val streaming: Boolean = false,
    val stats: String? = null,
)

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val modelName: String = "(no model)",
    val modelReady: Boolean = false,
    val busy: Boolean = false,
    val statusLine: String = "",
)

private const val SYSTEM_PROMPT =
    "You are Vireo, a concise offline study assistant running on the user's phone. " +
    "Answer clearly and briefly. If you are unsure, say so."

private const val MAX_HISTORY_MSGS = 8   // sent to the model (plus system)

class ChatViewModel(
    private val engine: LlmEngine,
    private val repo: ChatRepository,
    private val modelPath: String?,
    private val modelLabel: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChatState(messages = repo.load(), modelName = modelLabel)
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var genJob: Job? = null

    init {
        if (modelPath == null) {
            _state.value = _state.value.copy(statusLine = "No .gguf in app models folder")
        } else {
            viewModelScope.launch {
                _state.value = _state.value.copy(statusLine = "loading model…")
                runCatching { engine.load(modelPath, nCtx = 2048, nThreads = 2, nBatch = 128) }
                    .onSuccess {
                        _state.value = _state.value.copy(modelReady = true, statusLine = "ready")
                    }
                    .onFailure {
                        _state.value = _state.value.copy(statusLine = "load failed: ${it.message}")
                    }
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy || !_state.value.modelReady) return

        val withUser = _state.value.messages + UiMessage("user", prompt)
        _state.value = _state.value.copy(
            messages = withUser + UiMessage("assistant", "", streaming = true),
            busy = true,
            statusLine = "generating…",
        )
        repo.save(withUser)

        val transcript = buildList {
            add(ChatMsg("system", SYSTEM_PROMPT))
            withUser.takeLast(MAX_HISTORY_MSGS).forEach { add(ChatMsg(it.role, it.text)) }
        }

        genJob = viewModelScope.launch {
            val sb = StringBuilder()
            runCatching {
                engine.generate(transcript, GenParams(maxTokens = 512)).collect { ev ->
                    when (ev) {
                        is GenEvent.Token -> {
                            sb.append(ev.piece)
                            _state.value = _state.value.copy(messages = replaceLast(sb.toString(), true, null))
                        }
                        is GenEvent.Done -> {
                            val stats = "%.1f tok/s · %d tok".format(ev.tokPerSec, ev.nTokens)
                            val finalMsgs = replaceLast(sb.toString().trim(), false, stats)
                            _state.value = _state.value.copy(
                                messages = finalMsgs, busy = false,
                                statusLine = "ready · $stats",
                            )
                            repo.save(finalMsgs)
                        }
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    messages = replaceLast(sb.toString() + "\n[error: ${it.message}]", false, null),
                    busy = false, statusLine = "error",
                )
            }
        }
    }

    fun stop() { engine.cancel(); genJob?.cancel() }

    fun clear() {
        stop()
        _state.value = _state.value.copy(messages = emptyList(), busy = false, statusLine = "ready")
        repo.save(emptyList())
    }

    private fun replaceLast(text: String, streaming: Boolean, stats: String?): List<UiMessage> {
        val msgs = _state.value.messages.toMutableList()
        if (msgs.isNotEmpty()) {
            msgs[msgs.lastIndex] = msgs.last().copy(text = text, streaming = streaming, stats = stats)
        }
        return msgs
    }

    override fun onCleared() { stop() }
}
