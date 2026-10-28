package com.phisherman

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phisherman.data.AppDatabase
import com.phisherman.data.Decision
import com.phisherman.data.DecisionLog
import com.phisherman.data.Prefs
import com.phisherman.engine.Level
import com.phisherman.engine.Verdict
import com.phisherman.link.Forwarder
import com.phisherman.link.LinkAnalyser
import com.phisherman.ui.WarningActivity
import com.phisherman.ui.theme.PhishermanTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var protectionOn by mutableStateOf(false)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        protectionOn = isLinkHandler()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val recent = AppDatabase.get(this).decisions().recent(10)
        setContent {
            PhishermanTheme {
                Scaffold(Modifier.fillMaxSize()) { inner ->
                    val decisions by recent.collectAsState(initial = emptyList())
                    HomeScreen(
                        modifier = Modifier.padding(inner),
                        protectionOn = protectionOn,
                        onTurnOn = ::requestLinkHandler,
                        decisions = decisions,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        protectionOn = isLinkHandler()
    }

    private fun isLinkHandler(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) return rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
        return packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun requestLinkHandler() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    protectionOn: Boolean,
    onTurnOn: () -> Unit,
    decisions: List<Decision>,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<String, Verdict?>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val browsers = remember { Forwarder.browsers(ctx) }
    var browser by remember { mutableStateOf(Forwarder.targetBrowser(ctx)) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Phisherman", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        LinkAnalyser.loadError?.let {
            StatusCard(Color(0xFFB3261E), "Detection engine failed to load", "Links will be marked suspicious until this is fixed. $it")
        }

        if (protectionOn) {
            StatusCard(Color(0xFF1B6E3A), "Link protection is ON", "Links you tap in any app are checked before they open.")
        } else {
            StatusCard(Color(0xFFB26A00), "Link protection is OFF", "Set Phisherman as your browser app so it can check links before they open. Safe links still open in your normal browser.") {
                Button(onClick = onTurnOn) { Text("Turn on link protection") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check a link", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(input, { input = it; result = null }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a link") }, singleLine = true)
                Button(
                    enabled = input.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val v = LinkAnalyser.analyse(ctx, input)
                            checking = false
                            if (v != null && v.level != Level.SAFE) {
                                ctx.startActivity(WarningActivity.intent(ctx, input.trim(), v))
                            } else if (v != null) {
                                DecisionLog.recordAsync(ctx, v)
                            }
                            result = input to v
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check") }
                result?.let { (url, v) ->
                    when {
                        v == null -> Text("That is not a web link (http or https).")
                        v.level == Level.SAFE -> {
                            Text("✓ Looks safe · risk ${v.score}/100 · ${v.latencyMs} ms", color = Color(0xFF1B6E3A), fontWeight = FontWeight.SemiBold)
                            v.reasons.firstOrNull()?.let { Text(it.textEn) }
                            OutlinedButton(onClick = { Forwarder.openInRealBrowser(ctx, url.trim()) }) { Text("Open") }
                        }
                        else -> Text("⚠ ${v.level.name.lowercase()} · risk ${v.score}/100 · ${v.latencyMs} ms", color = Color(0xFFB3261E), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Safe links open in", fontWeight = FontWeight.SemiBold)
                if (browsers.isEmpty()) Text("No other browser installed. Install Chrome so safe links can open.")
                browsers.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { browser = b.packageName; Prefs.setChosenBrowser(ctx, b.packageName) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = browser == b.packageName, onClick = { browser = b.packageName; Prefs.setChosenBrowser(ctx, b.packageName) })
                        Text(b.label)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recent checks", fontWeight = FontWeight.SemiBold)
                if (decisions.isEmpty()) Text("Nothing checked yet.")
                val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                decisions.forEach { d ->
                    val color = when (d.level) { "DANGEROUS" -> Color(0xFFB3261E); "SUSPICIOUS" -> Color(0xFFB26A00); else -> Color(0xFF1B6E3A) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmt.format(Date(d.ts)), style = MaterialTheme.typography.bodySmall)
                        Text(d.level.lowercase(), color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(d.subject, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                        d.userAction?.let { Text(it.lowercase(), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (decisions.isNotEmpty()) {
                    OutlinedButton(onClick = { scope.launch { AppDatabase.get(ctx).decisions().clear() } }) { Text("Clear history") }
                }
            }
        }

        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(color: Color, title: String, body: String, action: @Composable (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Color.White)
            action?.invoke()
        }
    }
}
