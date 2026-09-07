package com.vireo

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vireo.chat.ChatRepository
import com.vireo.chat.ChatScreen
import com.vireo.chat.ChatViewModel
import com.vireo.diag.DiagnosticsScreen
import com.vireo.llm.LlmEngine
import com.vireo.llm.NativeLlm
import com.vireo.models.DownloadController
import com.vireo.models.InstalledModels
import com.vireo.models.ModelCatalog
import com.vireo.models.ModelsScreen
import com.vireo.models.ModelsViewModel
import com.vireo.notebook.NotebookListScreen
import com.vireo.notebook.NotebookScreen
import com.vireo.notebook.NotebookViewModel
import com.vireo.rag.Embedder
import com.vireo.rag.Notebook
import com.vireo.rag.NotebookRepository
import com.vireo.thermal.ThermalGovernor
import com.vireo.thermal.TelemetryLogger

class MainActivity : ComponentActivity() {

    private val engine = LlmEngine()
    private val embedder = Embedder()
    private var vmRef: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("Vireo", "ping -> " + runCatching { NativeLlm.ping() }.getOrElse { "FAIL ${it.message}" })

        DownloadController.init(this)
        val catalog = ModelCatalog.load(this)
        val installed = InstalledModels(this)
        val repo = ChatRepository(filesDir)
        val notebookRepo = NotebookRepository(filesDir)
        val governor = ThermalGovernor(this)
        val telemetry = TelemetryLogger(filesDir)
        val prefs = getSharedPreferences("vireo", Context.MODE_PRIVATE)
        val appCtx = applicationContext

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val totalRamMb = (ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
            / (1024 * 1024)).toInt()

        val chatFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(
                    engine = engine,
                    repo = repo,
                    governor = governor,
                    telemetry = telemetry,
                    initialActive = installed.active(catalog),
                    initialEcoMode = prefs.getBoolean("eco", false),
                    persistEcoMode = { prefs.edit().putBoolean("eco", it).apply() },
                ) as T
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { vmRef?.setForeground(true) }
            override fun onStop(owner: LifecycleOwner) { vmRef?.setForeground(false) }
        })

        setContent {
            MaterialTheme {
                var screen by rememberSaveable { mutableStateOf("chat") }
                var openNb by androidx.compose.runtime.remember { mutableStateOf<Notebook?>(null) }

                val chatVm: ChatViewModel = viewModel(factory = chatFactory)
                vmRef = chatVm

                when (screen) {
                    "models" -> {
                        val f = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ModelsViewModel(catalog, installed, totalRamMb, filesDir) { _ ->
                                    installed.active(catalog)?.let { chatVm.switchModel(it) }
                                    screen = "chat"
                                } as T
                        }
                        ModelsScreen(viewModel(factory = f), onBack = { screen = "chat" })
                    }

                    "notebooklist" -> NotebookListScreen(
                        repo = notebookRepo,
                        onOpen = { openNb = it; screen = "notebook" },
                        onBack = { screen = "chat" },
                    )

                    "diag" -> DiagnosticsScreen(
                        engine = engine,
                        embedder = embedder,
                        governor = governor,
                        telemetry = telemetry,
                        activeModelLabel = installed.active(catalog)?.label ?: "(none)",
                        installedIds = installed.installedIds(),
                        onBack = { screen = "chat" },
                    )

                    "notebook" -> {
                        val nb = openNb
                        if (nb == null) { screen = "notebooklist" }
                        else {
                            val f = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    NotebookViewModel(
                                        appContext = appCtx,
                                        repo = notebookRepo,
                                        embedder = embedder,
                                        chat = engine,
                                        governor = governor,
                                        nbId = nb.id,
                                        nbName = nb.name,
                                        embedModelPath = installed.embeddingPath(catalog),
                                    ) as T
                            }
                            NotebookScreen(
                                viewModel(key = "nb_${nb.id}", factory = f),
                                onBack = { screen = "notebooklist" },
                            )
                        }
                    }

                    else -> ChatScreen(
                        chatVm,
                        onOpenModels = { screen = "models" },
                        onOpenNotebooks = { screen = "notebooklist" },
                        onOpenDiagnostics = { screen = "diag" },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}
