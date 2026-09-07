package com.vireo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vireo.chat.ChatRepository
import com.vireo.chat.ChatScreen
import com.vireo.chat.ChatViewModel
import com.vireo.llm.LlmEngine
import com.vireo.llm.NativeLlm
import com.vireo.models.ModelStore

class MainActivity : ComponentActivity() {

    private val engine = LlmEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("Vireo", "ping -> " + runCatching { NativeLlm.ping() }.getOrElse { "FAIL ${it.message}" })

        val store = ModelStore(this)
        val repo = ChatRepository(filesDir)
        val active = store.active()

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(engine, repo, active?.absolutePath, store.label(active)) as T
        }

        setContent {
            MaterialTheme {
                val vm: ChatViewModel = viewModel(factory = factory)
                ChatScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}
