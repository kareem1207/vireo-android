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
import com.vireo.llm.LlmEngine
import com.vireo.llm.NativeLlm
import com.vireo.models.DownloadController
import com.vireo.models.InstalledModels
import com.vireo.models.ModelCatalog
import com.vireo.models.ModelsScreen
import com.vireo.models.ModelsViewModel
import com.vireo.thermal.ThermalGovernor
import com.vireo.thermal.TelemetryLogger

class MainActivity : ComponentActivity() {

    private val engine = LlmEngine()
    private var vmRef: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("Vireo", "ping -> " + runCatching { NativeLlm.ping() }.getOrElse { "FAIL ${it.message}" })

        DownloadController.init(this)
        val catalog = ModelCatalog.load(this)
        val installed = InstalledModels(this)
        val repo = ChatRepository(filesDir)
        val governor = ThermalGovernor(this)
        val telemetry = TelemetryLogger(filesDir)
        val prefs = getSharedPreferences("vireo", Context.MODE_PRIVATE)

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
                val chatVm: ChatViewModel = viewModel(factory = chatFactory)
                vmRef = chatVm

                if (screen == "models") {
                    val modelsFactory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ModelsViewModel(
                                catalog = catalog,
                                installed = installed,
                                totalRamMb = totalRamMb,
                                filesDir = filesDir,
                                onActivated = { _ ->
                                    installed.active(catalog)?.let { chatVm.switchModel(it) }
                                    screen = "chat"
                                },
                            ) as T
                    }
                    val modelsVm: ModelsViewModel = viewModel(factory = modelsFactory)
                    ModelsScreen(modelsVm, onBack = { screen = "chat" })
                } else {
                    ChatScreen(chatVm, onOpenModels = { screen = "models" })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}
