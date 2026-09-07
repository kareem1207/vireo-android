package com.vireo.notebook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vireo.llm.LlmEngine
import com.vireo.rag.Citation
import com.vireo.rag.DocExtractor
import com.vireo.rag.DocMeta
import com.vireo.rag.Embedder
import com.vireo.rag.NotebookRepository
import com.vireo.rag.RagEvent
import com.vireo.rag.RagPipeline
import com.vireo.rag.VectorStore
import com.vireo.thermal.ThermalGovernor
import com.vireo.thermal.ThermalPacer
import com.vireo.thermal.ThermalTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class NotebookUiState(
    val name: String = "",
    val docs: List<DocMeta> = emptyList(),
    val embedReady: Boolean = false,
    val embedModelMissing: Boolean = false,
    val status: String = "",
    val ingesting: Boolean = false,
    val ingestDone: Int = 0,
    val ingestTotal: Int = 0,
    val answering: Boolean = false,
    val question: String = "",
    val answer: String = "",
    val citations: List<Citation> = emptyList(),
)

class NotebookViewModel(
    private val appContext: Context,
    private val repo: NotebookRepository,
    private val embedder: Embedder,
    private val chat: LlmEngine,
    private val governor: ThermalGovernor,
    private val nbId: String,
    nbName: String,
    private val embedModelPath: String?,
) : ViewModel() {

    private val pipeline = RagPipeline(embedder, chat)
    private val store: VectorStore = repo.vectorStore(nbId)
    private val effectiveTier = MutableStateFlow(ThermalTier.NORMAL)

    private val _state = MutableStateFlow(NotebookUiState(name = nbName, docs = repo.docs(nbId)))
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()

    init {
        store.ensureLoaded()
        if (embedModelPath == null) {
            _state.value = _state.value.copy(
                embedModelMissing = true,
                status = "Download “EmbeddingGemma 300M” in Models to enable notebooks",
            )
        } else if (!embedder.isLoaded) {
            _state.value = _state.value.copy(status = "loading embedder…")
            viewModelScope.launch {
                runCatching { embedder.load(embedModelPath) }
                    .onSuccess { _state.value = _state.value.copy(embedReady = true, status = "ready") }
                    .onFailure { _state.value = _state.value.copy(status = "embedder load failed: ${it.message}") }
            }
        } else {
            _state.value = _state.value.copy(embedReady = true, status = "ready")
        }
    }

    fun setQuestion(q: String) { _state.value = _state.value.copy(question = q) }

    fun importDocument(uri: Uri, fileName: String) {
        if (_state.value.ingesting || !_state.value.embedReady) return
        _state.value = _state.value.copy(ingesting = true, ingestDone = 0, ingestTotal = 0, status = "reading $fileName…")
        viewModelScope.launch {
            runCatching {
                val pages = withContext(Dispatchers.IO) { DocExtractor.extract(appContext, uri, fileName) }
                require(pages.isNotEmpty()) { "no extractable text" }
                _state.value = _state.value.copy(status = "embedding $fileName…")
                val docId = UUID.randomUUID().toString().take(12)
                val nChunks = pipeline.ingest(store, docId, fileName, pages) { done, total ->
                    _state.value = _state.value.copy(ingestDone = done, ingestTotal = total)
                }
                repo.putDoc(nbId, DocMeta(docId, fileName, pages.size, nChunks))
            }.onFailure { _state.value = _state.value.copy(status = "import failed: ${it.message}") }
                .onSuccess { _state.value = _state.value.copy(status = "ready") }
            _state.value = _state.value.copy(ingesting = false, docs = repo.docs(nbId))
        }
    }

    fun removeDoc(docId: String) {
        repo.removeDoc(nbId, docId)
        _state.value = _state.value.copy(docs = repo.docs(nbId))
    }

    fun ask() {
        val q = _state.value.question.trim()
        if (q.isEmpty() || _state.value.answering || !_state.value.embedReady || !chat.isLoaded) return
        _state.value = _state.value.copy(answering = true, answer = "", citations = emptyList(), status = "retrieving…")

        val pacer = ThermalPacer(
            governor = governor,
            isForeground = { true },
            isCancelled = { !_state.value.answering },
            effectiveTier = effectiveTier,
        )

        viewModelScope.launch {
            val sb = StringBuilder()
            pipeline.answer(store, q, pacer).collect { ev ->
                when (ev) {
                    is RagEvent.Sources ->
                        _state.value = _state.value.copy(citations = ev.citations, status = "answering…")
                    is RagEvent.Token -> {
                        sb.append(ev.piece)
                        _state.value = _state.value.copy(answer = sb.toString())
                    }
                    is RagEvent.Done ->
                        _state.value = _state.value.copy(
                            answering = false,
                            status = "done · %.1f tok/s".format(ev.tokPerSec),
                        )
                    RagEvent.NoContext ->
                        _state.value = _state.value.copy(
                            answering = false, answer = "I couldn't find that in your notes.",
                            status = "no matching context",
                        )
                    is RagEvent.Error ->
                        _state.value = _state.value.copy(answering = false, status = ev.message)
                }
            }
        }
    }

    fun stop() { _state.value = _state.value.copy(answering = false); chat.cancel() }
}
