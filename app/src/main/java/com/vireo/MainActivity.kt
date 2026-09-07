package com.vireo

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
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
import com.vireo.models.ModelStore
import com.vireo.thermal.ThermalGovernor
import com.vireo.thermal.TelemetryLogger

class MainActivity : ComponentActivity() {

    private val engine = LlmEngine()
    private var vmRef: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("Vireo", "ping -> " + runCatching { NativeLlm.ping() }.getOrElse { "FAIL ${it.message}" })

        val store = ModelStore(this)
        val repo = ChatRepository(filesDir)
        val governor = ThermalGovernor(this)
        val telemetry = TelemetryLogger(filesDir)
        val active = store.active()
        val prefs = getSharedPreferences("vireo", Context.MODE_PRIVATE)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(
                    engine = engine,
                    repo = repo,
                    governor = governor,
                    telemetry = telemetry,
                    modelPath = active?.absolutePath,
                    modelLabel = store.label(active),
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
                val vm: ChatViewModel = viewModel(factory = factory)
                vmRef = vm
                ChatScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}
