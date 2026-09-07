package com.vireo.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vireo.llm.ChatMsg
import com.vireo.llm.GenEvent
import com.vireo.llm.GenParams
import com.vireo.llm.LlmEngine
import com.vireo.thermal.ThermalGovernor
import com.vireo.thermal.ThermalPacer
import com.vireo.thermal.ThermalSnapshot
import com.vireo.thermal.ThermalTier
import com.vireo.thermal.TelemetryLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    val thermal: ThermalSnapshot = ThermalSnapshot(),
    val effectiveTier: ThermalTier = ThermalTier.NORMAL,
    val ecoMode: Boolean = false,
) {
    val cooling: Boolean get() = busy && effectiveTier == ThermalTier.PAUSE
}

private const val SYSTEM_PROMPT =
    "You are Vireo, a concise offline study assistant running on the user's phone. " +
    "Answer clearly and briefly. If you are unsure, say so."

private const val MAX_HISTORY_MSGS = 8   // sent to the model (plus system)
private const val THREADS = 2            // M1 benchmark: best on this SoC

class ChatViewModel(
    private val engine: LlmEngine,
    private val repo: ChatRepository,
    private val governor: ThermalGovernor,
    private val telemetry: TelemetryLogger,
    private val modelPath: String?,
    private val modelLabel: String,
    initialEcoMode: Boolean = false,
    private val persistEcoMode: (Boolean) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChatState(messages = repo.load(), modelName = modelLabel, ecoMode = initialEcoMode)
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val effectiveTier = MutableStateFlow(ThermalTier.NORMAL)

    private var genJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var foreground = true

    // running token counter for mid-generation tok/s in telemetry
    @Volatile private var genTokens = 0
    @Volatile private var genStartMs = 0L

    init {
        governor.ecoMode = initialEcoMode
        governor.start()

        viewModelScope.launch { governor.snapshot.collect { _state.value = _state.value.copy(thermal = it) } }
        viewModelScope.launch { effectiveTier.collect { _state.value = _state.value.copy(effectiveTier = it) } }

        if (modelPath == null) {
            _state.value = _state.value.copy(statusLine = "no .gguf in models folder")
        } else {
            viewModelScope.launch {
                _state.value = _state.value.copy(statusLine = "loading model…")
                runCatching { engine.load(modelPath, nCtx = 2048, nThreads = THREADS, nBatch = 128) }
                    .onSuccess { _state.value = _state.value.copy(modelReady = true, statusLine = "ready") }
                    .onFailure { _state.value = _state.value.copy(statusLine = "load failed: ${it.message}") }
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy || !_state.value.modelReady) return

        stopRequested = false
        effectiveTier.value = ThermalTier.NORMAL
        genTokens = 0
        genStartMs = System.currentTimeMillis()

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

        val pacer = ThermalPacer(
            governor = governor,
            isForeground = { foreground },
            isCancelled = { stopRequested },
            effectiveTier = effectiveTier,
        )

        // 5-second telemetry sampler for the duration of this generation
        val ticker = viewModelScope.launch {
            while (isActive && _state.value.busy) {
                telemetry.row("tick", currentTps(), governor.snapshot.value, THREADS)
                delay(5_000)
            }
        }

        genJob = viewModelScope.launch {
            val sb = StringBuilder()
            runCatching {
                engine.generate(transcript, GenParams(maxTokens = 512), pacer).collect { ev ->
                    when (ev) {
                        is GenEvent.Token -> {
                            genTokens++
                            sb.append(ev.piece)
                            _state.value = _state.value.copy(messages = replaceLast(sb.toString(), true, null))
                        }
                        is GenEvent.Done -> {
                            val stats = "%.1f tok/s · %d tok".format(ev.tokPerSec, ev.nTokens)
                            val finalMsgs = replaceLast(sb.toString().trim().ifEmpty { "[no output]" }, false, stats)
                            _state.value = _state.value.copy(
                                messages = finalMsgs, busy = false,
                                statusLine = "ready · $stats",
                            )
                            repo.save(finalMsgs)
                            telemetry.row("done", ev.tokPerSec, governor.snapshot.value, THREADS)
                        }
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    messages = replaceLast(sb.toString() + "\n[error: ${it.message}]", false, null),
                    busy = false, statusLine = "error",
                )
            }
            ticker.cancel()
            effectiveTier.value = ThermalTier.NORMAL
        }
    }

    fun stop() {
        stopRequested = true
        engine.cancel()
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, statusLine = "stopped")
    }

    fun clear() {
        stop()
        _state.value = _state.value.copy(messages = emptyList(), busy = false, statusLine = "ready")
        repo.save(emptyList())
    }

    fun setForeground(fg: Boolean) {
        foreground = fg
        if (!fg && _state.value.busy) telemetry.row("background", currentTps(), governor.snapshot.value, THREADS)
    }

    fun setEcoMode(on: Boolean) {
        governor.ecoMode = on
        persistEcoMode(on)
        _state.value = _state.value.copy(ecoMode = on)
    }

    /** Debug: force a thermal tier so the ECO/PAUSE paths can be demoed on a cool device. */
    fun setDebugTier(tier: ThermalTier?) { governor.debugOverride = tier }

    val debugTier: ThermalTier? get() = governor.debugOverride

    private fun currentTps(): Float {
        val secs = (System.currentTimeMillis() - genStartMs) / 1000f
        return if (secs > 0.1f) genTokens / secs else 0f
    }

    private fun replaceLast(text: String, streaming: Boolean, stats: String?): List<UiMessage> {
        val msgs = _state.value.messages.toMutableList()
        if (msgs.isNotEmpty()) {
            msgs[msgs.lastIndex] = msgs.last().copy(text = text, streaming = streaming, stats = stats)
        }
        return msgs
    }

    override fun onCleared() {
        stop()
        governor.stop()
    }
}
