package com.phisherman.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.phisherman.data.DecisionLog
import com.phisherman.data.Prefs
import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Verdict
import com.phisherman.link.Forwarder
import com.phisherman.link.LinkAnalyser
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Full-screen warning shown before a risky link opens. Go back is the default. */
class WarningActivity : ComponentActivity() {
    private var decisionId = 0L
    private var actionTaken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val verdict = Json.decodeFromString<Verdict>(intent.getStringExtra(EXTRA_VERDICT) ?: return finish())
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val body = intent.getStringExtra(EXTRA_BODY)
        if (url.isEmpty() && body == null) return finish()
        decisionId = savedInstanceState?.getLong(STATE_ID) ?: 0L
        // SMS verdicts are already logged by the receiver; link warnings log here
        if (decisionId == 0L && verdict.channel == Channel.LINK) {
            lifecycleScope.launch { decisionId = DecisionLog.record(this@WarningActivity, verdict) }
        }

        setContent {
            var hindi by remember { mutableStateOf(Prefs.hindi(this)) }
            WarningScreen(
                url = url, body = body, verdict = verdict, hindi = hindi,
                onToggleLanguage = { hindi = !hindi; Prefs.setHindi(this, hindi) },
                onGoBack = { act("BACK"); finish() },
                onReport = {
                    act("REPORTED")
                    Toast.makeText(this, "Report screen arrives in Phase 5", Toast.LENGTH_SHORT).show()
                },
                onOpenAnyway = {
                    act("OPEN_ANYWAY")
                    LinkAnalyser.markOpenedAnyway(url)
                    if (!Forwarder.openInRealBrowser(this, url)) {
                        Toast.makeText(this, "No other browser installed", Toast.LENGTH_LONG).show()
                    }
                    finish()
                },
            )
        }
    }

    private fun act(action: String) {
        actionTaken = true
        DecisionLog.setActionAsync(this, decisionId, action)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_ID, decisionId)
    }

    override fun onDestroy() {
        if (isFinishing && !actionTaken) DecisionLog.setActionAsync(this, decisionId, "BACK")
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_VERDICT = "verdict"
        private const val EXTRA_BODY = "body"
        private const val STATE_ID = "decision_id"

        /** Warning for a message (opened from the SMS notification or the paste box). */
        fun smsIntent(ctx: Context, body: String, verdict: Verdict): Intent =
            Intent(ctx, WarningActivity::class.java)
                .putExtra(EXTRA_BODY, body.take(1000))
                .putExtra(EXTRA_VERDICT, Json.encodeToString(Verdict.serializer(), verdict))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        fun intent(ctx: Context, url: String, verdict: Verdict): Intent =
            Intent(ctx, WarningActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VERDICT, Json.encodeToString(Verdict.serializer(), verdict))
                // replace any older warning still in the background; otherwise Android would bring
                // the old one (same component, extras ignored) to the front for a new link
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

private val Danger = Color(0xFFB3261E)
private val DangerDark = Color(0xFF410E0B)
private val Amber = Color(0xFFB26A00)
private val AmberDark = Color(0xFF3D2500)

@Composable
private fun WarningScreen(
    url: String,
    body: String?,
    verdict: Verdict,
    hindi: Boolean,
    onToggleLanguage: () -> Unit,
    onGoBack: () -> Unit,
    onReport: () -> Unit,
    onOpenAnyway: () -> Unit,
) {
    val dangerous = verdict.level == Level.DANGEROUS
    val accent = if (dangerous) Danger else Amber
    val bg = if (dangerous) DangerDark else AmberDark
    var confirm by remember { mutableStateOf(false) }
    BackHandler(onBack = onGoBack)

    val t = { en: String, hi: String -> if (hindi) hi else en }
    val isSms = verdict.channel == Channel.SMS
    val host = verdict.subject
    val reg = if (isSms) host else LinkAnalyser.registrableDomain(host)
    val prefix = if (isSms) "" else host.removeSuffix(reg)

    Column(
        Modifier.fillMaxSize().background(bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Phisherman", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleLanguage) { Text(if (hindi) "English" else "हिंदी", color = Color.White) }
        }
        Text("⚠", fontSize = 56.sp, color = Color.White)
        Text(
            when {
                isSms && dangerous -> t("Scam message detected", "धोखाधड़ी वाला संदेश")
                isSms -> t("This message looks suspicious", "यह संदेश संदिग्ध लगता है")
                dangerous -> t("Dangerous link blocked", "ख़तरनाक लिंक रोका गया")
                else -> t("This link looks unsafe", "यह लिंक असुरक्षित लगता है")
            },
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp,
        )
        Text(
            buildAnnotatedString {
                if (isSms) withStyle(SpanStyle(color = Color.White.copy(alpha = 0.6f))) { append(t("From ", "भेजने वाला ")) }
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.6f))) { append(prefix) }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(reg.ifEmpty { host }) }
            },
            fontSize = 24.sp,
        )
        if (body != null) {
            Text(
                body.take(300),
                color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                t("Risk ${verdict.score}/100 · ", "जोखिम ${verdict.score}/100 · ") +
                    if (dangerous) t("Dangerous", "ख़तरनाक") else t("Suspicious", "संदिग्ध"),
                color = Color.White, fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { verdict.score / 100f },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = accent, trackColor = Color.White.copy(alpha = 0.2f),
            )
            Text(t("Estimated risk, not a guarantee", "अनुमानित जोखिम, गारंटी नहीं"), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Why we warned you", "हमने चेतावनी क्यों दी"), color = Color.White, fontWeight = FontWeight.SemiBold)
            verdict.reasons.forEach { r ->
                Text("•  " + if (hindi) (r.textHi ?: r.textEn) else r.textEn, color = Color.White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onGoBack,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = bg),
        ) {
            Text(if (isSms) t("Got it, I won't respond", "समझ गया, जवाब नहीं दूँगा") else t("Go back (safe)", "वापस जाएँ (सुरक्षित)"),
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (isSms) {
            Text(t("Do not click links in it, call back, or share OTP, PIN or card details.",
                "इसके लिंक पर क्लिक न करें, वापस कॉल न करें, और OTP, PIN या कार्ड की जानकारी न दें।"),
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (isSms) t("Report this message", "इस संदेश की शिकायत करें") else t("Report this link", "इस लिंक की शिकायत करें"),
                color = Color.White, fontSize = 16.sp)
        }
        if (!isSms) {
            TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(t("Open anyway", "फिर भी खोलें"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(t("Open this link anyway?", "फिर भी यह लिंक खोलें?")) },
            text = { Text(t("Never enter your OTP, PIN, password or card details on this site.",
                "इस साइट पर कभी भी अपना OTP, PIN, पासवर्ड या कार्ड की जानकारी न डालें।")) },
            confirmButton = { TextButton(onClick = { confirm = false; onOpenAnyway() }) { Text(t("Open anyway", "फिर भी खोलें")) } },
            dismissButton = { Button(onClick = { confirm = false; onGoBack() }) { Text(t("Go back", "वापस जाएँ")) } },
        )
    }
}
