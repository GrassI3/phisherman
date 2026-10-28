package com.phisherman

import com.phisherman.engine.Level
import com.phisherman.voice.CallModel
import com.phisherman.voice.CallSession
import com.phisherman.voice.VoiceRules
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Measures the call guard on the HELD-OUT calls written by ml/train_calls.py (ml/data/calls_test.tsv:
 * 20% of the public call datasets plus everyday conversations, split by template/call, never
 * trained on). Each call is replayed line by line through CallSession, the code the phone runs,
 * three ways: phrase rules only, trained model only, and both (what the app does). Writes
 * ml/results_voice.json. Skipped when the datasets are not downloaded.
 */
class VoiceDatasetEval {
    private val testFile = File("../../ml/data/calls_test.tsv")
    private val rulesJson = File("src/main/assets/voice_rules.json").readText()
    private val rules = VoiceRules(rulesJson)
    private val model = CallModel.load(File("src/main/assets/call_model.json").readText(),
        File("src/main/assets/thresholds.json").readText())
    /** Same rules with bands no score can reach: the session then reports the model alone. */
    private val silentRules = VoiceRules(rulesJson
        .replace(Regex(""""suspicious"\s*:\s*\d+"""), "\"suspicious\": 1000")
        .replace(Regex(""""dangerous"\s*:\s*\d+"""), "\"dangerous\": 1000"))

    private data class Call(val src: String, val scam: Boolean, val lines: List<String>)
    private data class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var tn: Int = 0)

    private var clock = 0L

    @Test
    fun evaluateOnHeldOutCalls() {
        assumeTrue("run ml/train_calls.py first", testFile.exists())
        val calls = testFile.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split("\t")
            Call(c[0], c[1] == "1", c[2].split(" || "))
        }
        val modes = linkedMapOf<String, () -> CallSession>(
            "rules" to { CallSession(rules, null) { clock } },
            "model" to { CallSession(silentRules, model) { clock } },
            "combined" to { CallSession(rules, model) { clock } },
        )
        val srcs = listOf("all") + calls.map { it.src }.distinct()
        val out = StringBuilder("{\n")
        srcs.forEachIndexed { i, src ->
            val part = if (src == "all") calls else calls.filter { it.src == src }
            out.append("  \"$src\": {\"n\": ${part.size}")
            for ((mode, session) in modes) {
                val red = Counts(); val alert = Counts()
                for (c in part) {
                    val max = replay(session(), c)
                    tally(red, c.scam, max == Level.DANGEROUS)
                    tally(alert, c.scam, max >= Level.SUSPICIOUS)
                }
                println("$src $mode: N=${part.size} red=$red alert=$alert")
                out.append(", \"$mode\": {\"red\": ${json(red)}, \"alert\": ${json(alert)}}")
            }
            out.append(if (i < srcs.size - 1) "},\n" else "}\n")
        }
        out.append("}\n")
        File("../../ml/results_voice.json").writeText(out.toString())
    }

    /** Lines 6 s apart; returns the highest level the call reached. */
    private fun replay(s: CallSession, c: Call): Level {
        var max = Level.SAFE
        for (l in c.lines) {
            clock += 6_000
            s.addFinal(l)
            val lv = s.update().level
            if (lv > max) max = lv
        }
        return max
    }

    private fun tally(c: Counts, scam: Boolean, flagged: Boolean) = when {
        scam && flagged -> c.tp++
        scam -> c.fn++
        flagged -> c.fp++
        else -> c.tn++
    }

    private fun json(c: Counts) = "{\"tp\": ${c.tp}, \"fp\": ${c.fp}, \"fn\": ${c.fn}, \"tn\": ${c.tn}}"
}
