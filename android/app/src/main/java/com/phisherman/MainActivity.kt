package com.phisherman

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phisherman.ui.theme.PhishermanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhishermanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Column(
                        modifier = Modifier.padding(inner).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Phisherman", style = MaterialTheme.typography.headlineLarge)
                        Text("Protection setup in progress")
                        // quoted in the pitch: demo phone model and Android version
                        Text("${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    }
                }
            }
        }
    }
}
