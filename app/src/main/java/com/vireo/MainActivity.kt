package com.vireo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vireo.llm.NativeLlm

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i("Vireo", "MainActivity started - offline, on-device")

        val ping = runCatching { NativeLlm.ping() }
            .getOrElse { "native FAILED: ${it.message}" }
        Log.i("Vireo", "NativeLlm.ping() -> $ping")

        setContent {
            MaterialTheme {
                Home(ping)
            }
        }
    }
}

@Composable
private fun Home(nativeStatus: String) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Vireo", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("offline. on-device.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Text(
                if (nativeStatus.startsWith("llm-ok")) "native runtime: OK" else nativeStatus,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
