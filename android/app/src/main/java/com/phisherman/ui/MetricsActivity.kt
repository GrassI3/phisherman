package com.phisherman.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phisherman.data.AppDatabase
import com.phisherman.engine.ScamLlm
import com.phisherman.ui.theme.Brand
import com.phisherman.ui.theme.PhishermanTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Results (PS4 11): precision, recall, fraud detection rate, accuracy per channel, from
 *  assets/results.json (written by ml/eval_report.py), plus speed measured on this phone. */
class MetricsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val results = runCatching {
            Json.parseToJsonElement(assets.open("results.json").bufferedReader().use { it.readText() }).jsonObject
        }.getOrNull()
        setContent { PhishermanTheme { MetricsScreen(results) } }
    }
}

private data class Latency(val channel: String, val n: Int, val p50: Long, val p95: Long)

@Composable
private fun MetricsScreen(r: JsonObject?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var latency by remember { mutableStateOf<List<Latency>>(emptyList()) }
    LaunchedEffect(Unit) {
        latency = AppDatabase.get(ctx).decisions().latencies().groupBy { it.channel }.map { (ch, rows) ->
            val s = rows.map { it.latencyMs }.sorted()
            Latency(ch, s.size, s[s.size / 2], s[minOf(s.size - 1, (s.size * 95) / 100)])
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("How well Phisherman spots scams, measured on messages and links it never saw while learning.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r == null) { Text("results.json is missing. Run ml/eval_report.py."); return@Column }

        r["channels"]!!.jsonArray.map { it.jsonObject }.forEach { ChannelCard(it) }
        r["link_threshold"]?.jsonObject?.let { ThresholdCard(it) }
        SpeedCard(latency)
        Glossary()
        Text("Generated ${r["generated"]!!.jsonPrimitive.content}. Small or synthetic test sets are marked; treat those as indicative.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

private val ICONS = mapOf("link" to "🔗", "sms" to "💬", "ai" to "🧠", "call" to "📞")

@Composable
private fun ChannelCard(c: JsonObject) {
    val h = c["headline"]!!.jsonObject
    var more by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(ICONS[c["icon"]!!.jsonPrimitive.content] ?: "•", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(c["name"]!!.jsonPrimitive.content, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(h["set"]!!.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Precision", h.d("precision"), "flagged items that were really scams", Modifier.weight(1f))
                Tile("Recall", h.d("recall"), "scams that were caught", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Fraud detection", h.d("fdr"), "of all fraud, % detected", Modifier.weight(1f), highlight = true)
                Tile("Accuracy", h.d("accuracy"), "all answers that were right", Modifier.weight(1f))
            }
            Text("F1 ${pct(h.d("f1"))}  ·  False alarms ${pct(h.d("fpr"))}  ·  Tested on ${"%,d".format(h.i("n"))} (${"%,d".format(h.i("scams"))} scams)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val extra = c["more"]!!.jsonArray.map { it.jsonObject }
            val notes = c["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (extra.isNotEmpty() || notes.isNotEmpty()) {
                TextButton(onClick = { more = !more }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (more) "Show less" else "Show more")
                }
            }
            if (more) {
                extra.forEach { m ->
                    Column {
                        Text(m["set"]!!.jsonPrimitive.content, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text("Precision ${pct(m.d("precision"))} · Recall / fraud detection ${pct(m.d("recall"))} · Accuracy ${pct(m.d("accuracy"))} · N ${"%,d".format(m.i("n"))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: Double, meaning: String, modifier: Modifier, highlight: Boolean = false, lowIsGood: Boolean = false) {
    val bg = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlight) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f))
        Text(pct(value), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = scoreColor(if (lowIsGood) 1 - value else value, fg))
        Text(meaning, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f), lineHeight = 15.sp)
    }
}

/** PS4 11.4: move the link threshold and watch precision trade against recall. */
@Composable
private fun ThresholdCard(t: JsonObject) {
    val curve = t["curve"]!!.jsonArray.map { it.jsonObject }
    val chosen = t.d("chosen")
    val start = curve.indexOfFirst { it.d("threshold") >= chosen }.coerceAtLeast(0)
    var pos by remember { mutableFloatStateOf(start.toFloat()) }
    val p = curve[pos.roundToInt().coerceIn(0, curve.lastIndex)]
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try it: link strictness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Stricter catches more phishing but raises more false alarms. The app uses ${"%.2f".format(chosen)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = pos, onValueChange = { pos = it }, valueRange = 0f..curve.lastIndex.toFloat(), steps = curve.size - 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Precision", p.d("precision"), "threshold ${"%.2f".format(p.d("threshold"))}", Modifier.weight(1f))
                Tile("Recall", p.d("recall"), "phishing caught", Modifier.weight(1f))
                Tile("False alarms", p.d("fpr"), "safe sites flagged", Modifier.weight(1f), lowIsGood = true)
            }
            Text("${"%,d".format(t.i("n"))} held-out websites", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeedCard(latency: List<Latency>) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚡ Speed on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${Build.MANUFACTURER} ${Build.MODEL}, measured from real checks", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (latency.isEmpty()) Text("No checks recorded yet.")
            latency.forEach {
                val name = when (it.channel) { "LINK" -> "Links"; "SMS" -> "Messages"; "VOICE" -> "Calls"; else -> it.channel }
                Text("$name: usually ${it.p50} ms, at most ${it.p95} ms for 95% of checks (${it.n} checks)")
            }
            if (ScamLlm.lastLatencyMs > 0) Text("On-device AI: last answer in ${ScamLlm.lastLatencyMs} ms")
        }
    }
}

@Composable
private fun Glossary() {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What the numbers mean", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            listOf(
                "Precision: when Phisherman warns, how often it is right.",
                "Recall: of all the scams, how many it caught.",
                "Fraud detection rate: the same as recall; the share of fraud attempts detected.",
                "Accuracy: share of all answers (scam or safe) that were right.",
                "False alarms: share of safe items wrongly warned about.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
private fun pct(x: Double) = if (x >= 0.9995) "100%" else "%.1f%%".format(x * 100)
private fun scoreColor(x: Double, normal: Color) = when { x >= 0.9 -> Brand.Safe; x >= 0.75 -> normal; else -> Brand.Warn }
