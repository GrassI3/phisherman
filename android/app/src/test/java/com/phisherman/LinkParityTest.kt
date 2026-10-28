package com.phisherman

import com.phisherman.link.Blocklist
import com.phisherman.link.LinkConfig
import com.phisherman.link.LinkDecider
import com.phisherman.link.LinkThresholds
import com.phisherman.link.UrlFeatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Python vs Kotlin parity (PS4 6.7): every feature within 1e-4 of ml/features.py. */
class LinkParityTest {
    private val assets = File("src/main/assets")
    private val cfg = LinkConfig(File(assets, "feature_order.json").readText(), File(assets, "brands.json").readText())
    private val rows = ParityCsv.read(javaClass.classLoader!!.getResource("parity_expected.csv")!!.readText())

    @Test
    fun featuresMatchPython() {
        assertEquals(30, rows.size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val got = UrlFeatures.compute(row.url, cfg)
            for ((i, name) in cfg.featureNames.withIndex()) {
                if (Math.abs(got[i] - row.features[i]) > 1e-4) {
                    failures += "${row.url} :: $name python=${row.features[i]} kotlin=${got[i]}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun blocklistMatchesPython() {
        val meta = Json.parseToJsonElement(File(assets, "blocklist.json").readText()).jsonObject
        val bl = Blocklist(File(assets, "blocklist.bloom").readBytes(), meta["m_bits"]!!.jsonPrimitive.long,
            meta["k"]!!.jsonPrimitive.long.toInt())
        assertFalse("docs.google.com" in bl)
        assertFalse("hdfcbank.com" in bl)
        assertFalse("bit.ly" in bl)
        // first real entry of the source list, kept by build_blocklist.py
        assertTrue("00000000000000000000000000000000000000000.xyz" in bl)
    }

    /** Every level / reason that does not depend on the model must match link_engine.py. */
    @Test
    fun rulesMatchPython() {
        val allow = Json.parseToJsonElement(File(assets, "allowlist.json").readText()).jsonObject["domains"]!!
            .jsonArray.map { it.jsonPrimitive.content }.toSet() + cfg.official
        val meta = Json.parseToJsonElement(File(assets, "blocklist.json").readText()).jsonObject
        val bl = Blocklist(File(assets, "blocklist.bloom").readBytes(), meta["m_bits"]!!.jsonPrimitive.long,
            meta["k"]!!.jsonPrimitive.long.toInt())
        // feed Python's own probability so only the rule logic is under test here
        val probs = rows.associate { it.url to it.prob }
        var current = ""
        val th = Json.parseToJsonElement(File(assets, "thresholds.json").readText()).jsonObject["link"]!!.jsonObject
        val decider = LinkDecider(cfg, allow, bl, LinkThresholds(
            th["suspicious"]!!.jsonPrimitive.content.toFloat(), th["dangerous"]!!.jsonPrimitive.content.toFloat())) {
            probs[current]!!.toFloat()
        }
        for (row in rows) {
            current = row.url
            val v = decider.decide(row.url)
            if (row.level == "IGNORED") {
                assertNull(row.url, v)
                continue
            }
            assertEquals(row.url, row.level, v!!.level.name)
            assertEquals(row.url, row.reason, v.ruleHits.first())
        }
        assertNull(decider.decide("tel:1930"))
    }
}

data class ParityRow(val url: String, val features: List<Double>, val prob: Double, val level: String, val reason: String)

object ParityCsv {
    fun read(text: String): List<ParityRow> = text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = split(line)
        ParityRow(c[0], c.subList(1, 20).map { it.toDouble() }, c[20].toDouble(), c[21], c[22])
    }.toList()

    /** Minimal CSV: commas, double-quoted fields, "" escapes. */
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
