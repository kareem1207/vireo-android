package com.vireo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vireo.llm.GenEvent
import com.vireo.llm.GenParams
import com.vireo.llm.LlmEngine
import com.vireo.llm.NativeLlm
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val engine = LlmEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ping = runCatching { NativeLlm.ping() }.getOrElse { "native FAILED: ${it.message}" }
        Log.i("Vireo", "ping -> $ping")
        setContent { MaterialTheme { M1Harness(engine, getExternalFilesDir(null) ?: filesDir) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
    }
}

private const val TEST_PROMPT = "Explain photosynthesis to a 12-year-old in exactly 3 sentences."

@Composable
private fun M1Harness(engine: LlmEngine, filesDir: File) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("idle") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    var threads by remember { mutableStateOf(2) }
    val modelsDir = File(filesDir, "models")
    val candidates = listOf(
        "qwen2.5-0.5b-instruct-q4_0.gguf",
        "Llama-3.2-1B-Instruct-Q4_0.gguf",
    )

    fun run(modelFile: String) {
        if (running) return
        running = true
        output = ""
        status = "loading $modelFile … (threads=$threads)"
        scope.launch {
            try {
                val path = File(modelsDir, modelFile).absolutePath
                val t0 = System.currentTimeMillis()
                engine.load(path, nCtx = 2048, nThreads = threads, nBatch = 128)
                status = "loaded in ${System.currentTimeMillis() - t0} ms · generating …"
                engine.generate(TEST_PROMPT, GenParams(maxTokens = 200)).collect { ev ->
                    when (ev) {
                        is GenEvent.Token -> output += ev.piece
                        is GenEvent.Done -> status =
                            "done · gen ${"%.1f".format(ev.tokPerSec)} tok/s · " +
                            "prompt ${"%.1f".format(ev.promptTokPerSec)} tok/s · ${ev.nTokens} tok"
                    }
                }
            } catch (t: Throwable) {
                status = "ERROR: ${t.message}"
                Log.e("Vireo", "generation failed", t)
            } finally {
                running = false
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Vireo — M1 native inference", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { m ->
                    Button(onClick = { run(m) }, enabled = !running) {
                        Text(m.substringBefore("-instruct").substringBefore("-Instruct"))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4, 6).forEach { t ->
                    Button(onClick = { threads = t }, enabled = !running) {
                        Text(if (t == threads) "[$t]" else "$t")
                    }
                }
            }
            if (running) Button(onClick = { engine.cancel() }) { Text("Stop") }
            Spacer(Modifier.height(4.dp))
            Text(
                output.ifEmpty { "(output will stream here)" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
    }
}
