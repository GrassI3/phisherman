package com.phisherman

import android.Manifest
import android.annotation.SuppressLint
import android.os.PowerManager
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
import androidx.core.content.ContextCompat
import com.phisherman.link.LinkAnalyser
import android.content.ComponentName
import com.phisherman.sms.ChatScreenReader
import com.phisherman.sms.MessageNotificationListener
import com.phisherman.sms.SmsAnalyser
import com.phisherman.voice.CallGuardActivity
import com.phisherman.ui.WarningActivity
import com.phisherman.ui.theme.PhishermanTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var protectionOn by mutableStateOf(false)
    private var smsOn by mutableStateOf(false)
    private var chatOn by mutableStateOf(false)
    private var batteryOk by mutableStateOf(true)
    private var openChatOn by mutableStateOf(false)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        protectionOn = isLinkHandler()
    }
    private val smsPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsOn = hasSmsProtection()
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
                        smsOn = smsOn,
                        onTurnOnSms = ::requestSmsProtection,
                        chatOn = chatOn,
                        onTurnOnChat = ::openListenerSettings,
                        batteryOk = batteryOk,
                        onFixBattery = ::requestBatteryExemption,
                        openChatOn = openChatOn,
                        onTurnOnOpenChat = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        decisions = decisions,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        protectionOn = isLinkHandler()
        smsOn = hasSmsProtection()
        chatOn = MessageNotificationListener.isEnabled(this)
        openChatOn = ChatScreenReader.isEnabled(this)
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")  // a security app must keep checking messages in the background
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openListenerSettings() {
        val component = ComponentName(this, MessageNotificationListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else null
        try {
            startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasSmsProtection(): Boolean =
        granted(Manifest.permission.RECEIVE_SMS) && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))

    private fun requestSmsProtection() {
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        smsPermissions.launch(perms.toTypedArray())
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
    smsOn: Boolean,
    onTurnOnSms: () -> Unit,
    chatOn: Boolean,
    onTurnOnChat: () -> Unit,
    batteryOk: Boolean,
    onFixBattery: () -> Unit,
    openChatOn: Boolean,
    onTurnOnOpenChat: () -> Unit,
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

        if (smsOn) {
            StatusCard(Color(0xFF1B6E3A), "SMS protection is ON", "New messages are checked as they arrive. Risky ones get a warning notification.")
        } else {
            StatusCard(Color(0xFFB26A00), "SMS protection is OFF", "Allow Phisherman to see incoming SMS and show notifications. Messages are checked on this phone and never uploaded.") {
                Button(onClick = onTurnOnSms) { Text("Turn on SMS protection") }
            }
        }

        if (chatOn) {
            StatusCard(Color(0xFF1B6E3A), "App notification protection is ON", "New notifications from every app (RCS chats, WhatsApp, Instagram, Gmail and more) are checked on this phone.")
        } else {
            StatusCard(Color(0xFFB26A00), "App notification protection is OFF",
                "Scams arrive as RCS chats, WhatsApp, Instagram DMs and emails, not only SMS. Allow Phisherman to read notifications so it can check them. Text is checked on this phone and never stored.") {
                Button(onClick = onTurnOnChat) { Text("Turn on chat protection") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Call guard", fontWeight = FontWeight.SemiBold)
                Text("Listens to a call on speaker and warns you, with strong vibration, when it sounds like a scam. Works offline; nothing is recorded.")
                Button(onClick = { ctx.startActivity(Intent(ctx, CallGuardActivity::class.java)) }) { Text("Open call guard") }
            }
        }

        if (openChatOn) {
            StatusCard(Color(0xFF1B6E3A), "Open-chat protection is ON", "Incoming messages in the chat you have open (WhatsApp, Instagram, Telegram, Messages) are checked too.")
        } else {
            StatusCard(Color(0xFFB26A00), "Open-chat protection is OFF",
                "When a chat is open, apps send no notification, so new messages there are not checked. Turn on \"Phisherman open-chat check\" in Accessibility. If Android says \"Restricted setting\", open App info > ⋮ > Allow restricted settings first.") {
                Button(onClick = onTurnOnOpenChat) { Text("Turn on open-chat protection") }
            }
        }

        if ((smsOn || chatOn) && !batteryOk) {
            StatusCard(Color(0xFFB26A00), "Keep protection running",
                "Your phone may put Phisherman to sleep in the background, and then messages are not checked until you open the app. Allow it to run without battery restrictions. On Samsung also check Settings > Battery > Background usage limits.") {
                Button(onClick = onFixBattery) { Text("Allow background checking") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check a link or message", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(input, { input = it; result = null }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a link or a whole SMS") }, minLines = 1, maxLines = 6)
                Button(
                    enabled = input.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val text = input.trim()
                            // one token with a dot = a link; anything else is checked as a message
                            val isLink = text.none { it.isWhitespace() } && '.' in text
                            val v = if (isLink) LinkAnalyser.analyse(ctx, text) else SmsAnalyser.analyse(ctx, null, text)
                            checking = false
                            when {
                                v == null -> {}
                                v.level == Level.SAFE -> DecisionLog.recordAsync(ctx, v)
                                isLink -> ctx.startActivity(WarningActivity.intent(ctx, text, v))
                                else -> {
                                    DecisionLog.recordAsync(ctx, v)
                                    ctx.startActivity(WarningActivity.smsIntent(ctx, text, v))
                                }
                            }
                            result = (if (isLink) text else "") to v
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check") }
                result?.let { (url, v) ->
                    when {
                        v == null -> Text("That is not a web link (http or https).")
                        v.level == Level.SAFE -> {
                            Text("✓ Looks safe · risk ${v.score}/100 · ${v.latencyMs} ms", color = Color(0xFF1B6E3A), fontWeight = FontWeight.SemiBold)
                            v.reasons.firstOrNull()?.let { Text(it.textEn) }
                            if (url.isNotEmpty()) OutlinedButton(onClick = { Forwarder.openInRealBrowser(ctx, url) }) { Text("Open") }
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
