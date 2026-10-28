package com.phisherman

import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Verdict
import com.phisherman.link.LinkConfig
import com.phisherman.sms.SmsDecider
import com.phisherman.sms.SmsModel
import com.phisherman.sms.SmsNormalizer
import com.phisherman.sms.SmsThresholds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kotlin SMS pipeline vs ml/normalize.py + ml/sms_engine.py on the parity messages. */
class SmsParityTest {
    private val assets = File("src/main/assets")
    private val cfg = LinkConfig(File(assets, "feature_order.json").readText(), File(assets, "brands.json").readText())
    private val model = SmsModel(File(assets, "sms_model.json").readText())

    private data class Row(val sender: String, val text: String, val norm: String, val urls: List<String>,
                           val prob: Double, val level: String, val hits: List<String>, val linkLevel: String)

    private val rows = javaClass.classLoader!!.getResource("sms_parity_expected.tsv")!!.readText(Charsets.UTF_8)
        .lines().drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split("\t")
            Row(c[0], c[1], c[2], c[3].split(" ").filter { it.isNotEmpty() }, c[4].toDouble(), c[5],
                c[6].split(",").filter { it.isNotEmpty() }, c.getOrElse(7) { "" })
        }

    @Test
    fun normalizerMatchesPython() {
        assertTrue(rows.size >= 50)
        val bad = rows.mapNotNull { r ->
            val n = SmsNormalizer.normalize(r.text)
            if (n.text != r.norm || n.urls != r.urls) "${r.text}\n  python=${r.norm} ${r.urls}\n  kotlin=${n.text} ${n.urls}" else null
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun probabilityMatchesPython() {
        val bad = rows.mapNotNull { r ->
            val p = model.prob(r.norm)
            if (Math.abs(p - r.prob) > 1e-6) "${r.text}: python=${r.prob} kotlin=$p" else null
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun trustedNamedSenderIsCappedOnlyWhenNoRuleFires() {
        val th = Json.parseToJsonElement(File(assets, "thresholds.json").readText()).jsonObject["sms"]!!.jsonObject
        val d = SmsDecider(model, cfg, SmsThresholds(th["suspicious"]!!.jsonPrimitive.double, th["dangerous"]!!.jsonPrimitive.double)) { null }
        val txn = "Dear Customer, your A/c XX1234 is debited by Rs 5,000 on 28-10-26. If not done by you, call our customer care immediately."
        assertEquals(Level.SAFE, d.decide("Indian Overseas Bank", txn, trustedSender = true).verdict.level)
        val ask = "Dear Customer, your account is blocked. Share the OTP you received to unblock it."
        assertEquals(Level.DANGEROUS, d.decide("Indian Overseas Bank", ask, trustedSender = true).verdict.level)
        assertTrue(com.phisherman.sms.SmsHandler.isTrustedName("Indian Overseas Bank", "Messages"))
        assertTrue(!com.phisherman.sms.SmsHandler.isTrustedName("099752 17785", "Messages"))
        assertTrue(!com.phisherman.sms.SmsHandler.isTrustedName("Konnad Arrives", "WhatsApp"))
    }

    /** Link levels come from the link engine (tested separately); stub them with Python's result. */
    @Test
    fun decisionsMatchPython() {
        val th = Json.parseToJsonElement(File(assets, "thresholds.json").readText()).jsonObject["sms"]!!.jsonObject
        val thresholds = SmsThresholds(th["suspicious"]!!.jsonPrimitive.double, th["dangerous"]!!.jsonPrimitive.double)
        val bad = mutableListOf<String>()
        for (r in rows) {
            val stub: (String) -> Verdict? = { url ->
                if (r.linkLevel.isEmpty()) null
                else Verdict(Channel.LINK, 0, Level.valueOf(r.linkLevel), emptyList(), null, emptyList(), 0, url)
            }
            val d = SmsDecider(model, cfg, thresholds, stub).decide(r.sender, r.text)
            val hits = d.verdict.ruleHits.filter { it != "model" }
            if (d.verdict.level.name != r.level || hits != r.hits) {
                bad += "${r.sender} | ${r.text}\n  python=${r.level} ${r.hits}\n  kotlin=${d.verdict.level} $hits"
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }
}
