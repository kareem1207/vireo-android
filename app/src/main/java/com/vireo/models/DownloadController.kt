package com.vireo.models

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class DownloadPhase { RUNNING, VERIFYING, INSTALLED, FAILED }

data class DownloadUiState(
    val id: String,
    val phase: DownloadPhase,
    val bytes: Long = 0,
    val total: Long = 0,
    val bytesPerSec: Long = 0,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * App-scoped download manager. Lives past any screen/ViewModel so a download
 * keeps running while the user is elsewhere in the app. (A foreground service to
 * also survive full process death is a later refinement.)
 */
object DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    private lateinit var installed: InstalledModels
    private lateinit var downloader: ModelDownloader
    @Volatile private var ready = false

    private val _states = MutableStateFlow<Map<String, DownloadUiState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadUiState>> = _states.asStateFlow()

    fun init(context: Context) {
        if (ready) return
        installed = InstalledModels(context.applicationContext)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        downloader = ModelDownloader(client)
        ready = true
    }

    fun start(model: CatalogModel) {
        if (jobs[model.id]?.isActive == true) return
        val dest = installed.fileFor(model.id)
        put(DownloadUiState(model.id, DownloadPhase.RUNNING, 0, model.sizeBytes))
        jobs[model.id] = scope.launch {
            downloader.download(model, dest).collect { p ->
                when (p) {
                    is DownloadProgress.Running ->
                        put(DownloadUiState(model.id, DownloadPhase.RUNNING, p.bytes, p.total, p.bytesPerSec))
                    DownloadProgress.Verifying ->
                        put(DownloadUiState(model.id, DownloadPhase.VERIFYING, model.sizeBytes, model.sizeBytes))
                    DownloadProgress.Done ->
                        put(DownloadUiState(model.id, DownloadPhase.INSTALLED, model.sizeBytes, model.sizeBytes))
                    is DownloadProgress.Failed ->
                        put(DownloadUiState(model.id, DownloadPhase.FAILED, error = p.message))
                }
            }
        }.also { it.invokeOnCompletion { jobs.remove(model.id) } }
    }

    /** Stop a running download. The .part file is kept so it can resume later. */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _states.value = _states.value - id
    }

    fun clearState(id: String) { _states.value = _states.value - id }

    private fun put(s: DownloadUiState) { _states.value = _states.value + (s.id to s) }
}
