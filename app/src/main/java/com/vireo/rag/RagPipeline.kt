package com.vireo.rag

import com.vireo.llm.ChatMsg
import com.vireo.llm.GenEvent
import com.vireo.llm.GenParams
import com.vireo.llm.LlmEngine
import com.vireo.llm.NoPacer
import com.vireo.llm.TokenPacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class Citation(val index: Int, val fileName: String, val page: Int, val snippet: String)

sealed interface RagEvent {
    data class Sources(val citations: List<Citation>) : RagEvent
    data class Token(val piece: String) : RagEvent
    data class Done(val tokPerSec: Float) : RagEvent
    data object NoContext : RagEvent
    data class Error(val message: String) : RagEvent
}

private const val SYS =
    "You answer questions using ONLY the numbered context provided. Cite sources inline like [1]. " +
    "If the answer is not in the context, reply exactly: I couldn't find that in your notes."

class RagPipeline(
    private val embedder: Embedder,
    private val chat: LlmEngine,
) {

    /** Chunk + embed every page of one document into [store]. Returns chunk count. */
    suspend fun ingest(
        store: VectorStore,
        docId: String,
        fileName: String,
        pages: List<Page>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        store.ensureLoaded()
        store.removeDoc(docId)
        val raw = Chunker.chunk(pages)
        raw.forEachIndexed { i, rc ->
            val v = embedder.embedDocument(rc.text)
            store.add(Chunk(docId, fileName, rc.page, rc.charStart, rc.text), v)
            onProgress(i + 1, raw.size)
        }
        store.persist()
        return raw.size
    }

    /** Retrieve → grounded, streamed answer. Citations come from retrieval, not the model. */
    fun answer(store: VectorStore, question: String, pacer: TokenPacer = NoPacer): Flow<RagEvent> = flow {
        val qv = runCatching { embedder.embedQuery(question) }
            .getOrElse { emit(RagEvent.Error("embedding failed: ${it.message}")); return@flow }

        val hits = store.search(qv, k = 4, minScore = 0.20f)
        if (hits.isEmpty()) { emit(RagEvent.NoContext); return@flow }

        val citations = hits.mapIndexed { i, (c, _) ->
            Citation(i + 1, c.fileName, c.page, c.text.take(180).replace(Regex("\\s+"), " ").trim())
        }
        emit(RagEvent.Sources(citations))

        val contextBlock = hits.mapIndexed { i, (c, _) ->
            "[${i + 1}] (${c.fileName} p.${c.page}) ${c.text}"
        }.joinToString("\n\n")
        val user = "Context:\n$contextBlock\n\nQuestion: $question"

        chat.generate(
            listOf(ChatMsg("system", SYS), ChatMsg("user", user)),
            GenParams(maxTokens = 400),
            pacer,
        ).collect { ev ->
            when (ev) {
                is GenEvent.Token -> emit(RagEvent.Token(ev.piece))
                is GenEvent.Done -> emit(RagEvent.Done(ev.tokPerSec))
            }
        }
    }.flowOn(Dispatchers.Default)
}
