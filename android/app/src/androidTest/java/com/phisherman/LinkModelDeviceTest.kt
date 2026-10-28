package com.phisherman

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phisherman.link.LinkAnalyser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On the phone: ONNX probabilities within 0.01 of Python, and the same final level / reason. */
@RunWith(AndroidJUnit4::class)
class LinkModelDeviceTest {

    @Test
    fun engineMatchesPythonOnDevice() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val csv = inst.context.assets.open("parity_expected.csv").bufferedReader().use { it.readText() }
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { splitCsv(it) }.toList()
        assertTrue(rows.size == 30)

        LinkAnalyser.init(app)
        assertNull("engine failed to load: ${LinkAnalyser.loadError}", LinkAnalyser.loadError)

        val failures = mutableListOf<String>()
        for (c in rows) {
            val url = c[0]
            val prob = c[20].toDouble()
            val level = c[21]
            val reason = c[22]
            val v = LinkAnalyser.analyse(app, url)
            if (level == "IGNORED") {
                if (v != null) failures += "$url expected ignored, got ${v.level}"
                continue
            }
            if (v == null) { failures += "$url got null"; continue }
            if (v.level.name != level || v.ruleHits.first() != reason) failures += "$url python=$level/$reason kotlin=${v.level}/${v.ruleHits.first()}"
            v.modelProb?.let { if (Math.abs(it - prob) > 0.01) failures += "$url prob python=$prob kotlin=$it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun splitCsv(line: String): List<String> {
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
