package com.vireo.models

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

sealed interface DownloadProgress {
    data class Running(val bytes: Long, val total: Long, val bytesPerSec: Long) : DownloadProgress
    data object Verifying : DownloadProgress
    data object Done : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

/** Resumable GGUF download with SHA-256 verification. */
class ModelDownloader(private val client: OkHttpClient) {

    fun download(model: CatalogModel, dest: File): Flow<DownloadProgress> = flow {
        val part = File(dest.parentFile, dest.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > model.sizeBytes) { part.delete(); have = 0L }

        val req = Request.Builder().url(model.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { emit(DownloadProgress.Failed("HTTP ${resp.code}")); return@flow }

            // 200 => server ignored Range; restart from scratch. 206 => append.
            val append = resp.code == 206 && have > 0
            if (!append) have = 0L

            val body = resp.body ?: run { emit(DownloadProgress.Failed("empty body")); return@flow }
            val total = if (append) have + body.contentLength() else body.contentLength().let {
                if (it > 0) it else model.sizeBytes
            }

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(have)
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var lastEmit = 0L
                    var windowBytes = 0L
                    var windowStart = System.currentTimeMillis()
                    while (true) {
                        currentCoroutineContext().ensureActive()   // cooperative cancel — .part is kept
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        have += n
                        windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            val secs = (now - windowStart).coerceAtLeast(1) / 1000.0
                            emit(DownloadProgress.Running(have, total, (windowBytes / secs).toLong()))
                            lastEmit = now; windowBytes = 0; windowStart = now
                        }
                    }
                }
            }
        }

        emit(DownloadProgress.Verifying)
        val actual = sha256(part)
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            Log.e("Vireo", "sha mismatch ${model.id}: want ${model.sha256} got $actual")
            part.delete()
            emit(DownloadProgress.Failed("checksum mismatch — deleted, try again"))
            return@flow
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { emit(DownloadProgress.Failed("could not finalize file")); return@flow }
        emit(DownloadProgress.Done)
    }.flowOn(Dispatchers.IO)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}
