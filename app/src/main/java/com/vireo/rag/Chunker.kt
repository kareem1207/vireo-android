package com.vireo.rag

data class RawChunk(val page: Int, val charStart: Int, val text: String)

/** Sentence-aware chunking, ~[target] chars with ~[overlap] chars carried between chunks. */
object Chunker {

    private val SENTENCE = Regex("(?<=[.!?])\\s+|\\n{2,}")

    fun chunk(pages: List<Page>, target: Int = 900, overlap: Int = 140): List<RawChunk> {
        val out = mutableListOf<RawChunk>()
        for (page in pages) {
            val sentences = page.text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            var start = 0
            var cursor = 0
            for (sent in sentences) {
                if (sb.isNotEmpty() && sb.length + sent.length + 1 > target) {
                    out += RawChunk(page.number, start, sb.toString().trim())
                    val tail = sb.takeLast(overlap).toString()
                    start = cursor - tail.length
                    sb.setLength(0)
                    sb.append(tail).append(' ')
                }
                if (sb.isEmpty()) start = cursor
                sb.append(sent).append(' ')
                cursor += sent.length + 1
            }
            if (sb.isNotBlank()) out += RawChunk(page.number, start, sb.toString().trim())
        }
        return out
    }
}
