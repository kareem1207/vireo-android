package com.vireo.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class ModelRow(
    val model: CatalogModel,
    val installed: Boolean,
    val active: Boolean,
    val download: DownloadUiState?,
    val ramWarn: Boolean,
)

data class ModelsUiState(
    val rows: List<ModelRow> = emptyList(),
    val freeStorageMb: Long = 0,
    val totalRamMb: Int = 0,
)

class ModelsViewModel(
    private val catalog: List<CatalogModel>,
    private val installed: InstalledModels,
    private val totalRamMb: Int,
    private val filesDir: File,
    private val onActivated: (String) -> Unit,
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    val state: StateFlow<ModelsUiState> =
        combine(DownloadController.states, refresh) { dl, _ ->
            val activeId = installed.activeId()
            ModelsUiState(
                rows = catalog.map { m ->
                    val d = dl[m.id]
                    val onDisk = installed.isInstalled(m.id) || d?.phase == DownloadPhase.INSTALLED
                    ModelRow(
                        model = m,
                        installed = onDisk,
                        active = m.id == activeId,
                        download = d,
                        ramWarn = m.minRamMb > totalRamMb,
                    )
                },
                freeStorageMb = filesDir.usableSpace / (1024 * 1024),
                totalRamMb = totalRamMb,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    fun download(m: CatalogModel) = DownloadController.start(m)
    fun cancelDownload(id: String) = DownloadController.cancel(id)
    fun dismissError(id: String) = DownloadController.clearState(id)

    fun delete(id: String) {
        installed.delete(id)
        DownloadController.clearState(id)
        refresh.value++
    }

    fun activate(id: String) {
        if (!installed.isInstalled(id)) return
        installed.setActive(id)
        onActivated(id)
        refresh.value++
    }
}
