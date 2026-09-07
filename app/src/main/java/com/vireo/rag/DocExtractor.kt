package com.vireo.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

data class Page(val number: Int, val text: String)

/** Pulls page-numbered plain text out of a PDF / TXT / MD file. */
object DocExtractor {

    private const val MAX_PDF_PAGES = 60
    @Volatile private var pdfInit = false

    fun extract(context: Context, uri: Uri, fileName: String): List<Page> {
        val lower = fileName.lowercase()
        val isPdf = lower.endsWith(".pdf") ||
            context.contentResolver.getType(uri) == "application/pdf"
        return if (isPdf) extractPdf(context, uri) else extractText(context, uri)
    }

    private fun extractText(context: Context, uri: Uri): List<Page> {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()
        return listOf(Page(1, text))
    }

    private fun extractPdf(context: Context, uri: Uri): List<Page> {
        if (!pdfInit) { PDFBoxResourceLoader.init(context.applicationContext); pdfInit = true }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val n = minOf(doc.numberOfPages, MAX_PDF_PAGES)
                val stripper = PDFTextStripper()
                (1..n).mapNotNull { p ->
                    stripper.startPage = p
                    stripper.endPage = p
                    val t = runCatching { stripper.getText(doc) }.getOrDefault("")
                    if (t.isBlank()) null else Page(p, t.trim())
                }.also {
                    if (doc.numberOfPages > MAX_PDF_PAGES) {
                        Log.w("Vireo", "PDF has ${doc.numberOfPages} pages; only first $MAX_PDF_PAGES ingested")
                    }
                }
            }
        } ?: emptyList()
    }
}
