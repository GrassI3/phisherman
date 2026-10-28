package com.phisherman.voice

import com.phisherman.engine.Level
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Phrase categories from voice_rules.json (PS4 9.3). */
class VoiceRules(json: String) {
    class Category(val id: String, val weight: Int, val en: String, val hi: String, val latin: List<Regex>, val devanagari: List<String>)

    private val root = Json.parseToJsonElement(json).jsonObject
    val version: String = root["version"]!!.jsonPrimitive.content
    val windowMs: Long = root["window_seconds"]!!.jsonPrimitive.int * 1000L
    val suspicious: Int = root["bands"]!!.jsonObject["suspicious"]!!.jsonPrimitive.int
    val dangerous: Int = root["bands"]!!.jsonObject["dangerous"]!!.jsonPrimitive.int
    class Combo(val id: String, val bonus: Int, val anyOf: Set<String>, val withAnyOf: Set<String>)

    /** Each combo adds its bonus once when a category from both sets was heard in the window. */
    val combos: List<Combo> = root["combos"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        Combo(o["id"]!!.jsonPrimitive.content, o["bonus"]!!.jsonPrimitive.int,
            o["any_of"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            o["with_any_of"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
    val categories: List<Category> = root["categories"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        Category(
            o["id"]!!.jsonPrimitive.content, o["weight"]!!.jsonPrimitive.int,
            o["en"]!!.jsonPrimitive.content, o["hi"]!!.jsonPrimitive.content,
            o["latin"]!!.jsonArray.map { Regex("(?<![a-z])" + Regex.escape(it.jsonPrimitive.content) + "(?![a-z])") },
            o["devanagari"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

data class VoiceScore(val score: Int, val level: Level, val categories: List<String>)

class VoiceScorer(private val rules: VoiceRules) {

    /** Scores one transcript window. Stateless; CallSession adds the window and hysteresis. */
    fun score(transcript: String): VoiceScore {
        val text = normalize(transcript)
        val found = rules.categories.filter { c -> matches(c, text) }.map { it.id }
        var score = rules.categories.filter { it.id in found }.sumOf { it.weight }
        for (c in rules.combos) if (found.any { it in c.anyOf } && found.any { it in c.withAnyOf }) score += c.bonus
        score = score.coerceAtMost(100)
        val level = when {
            score >= rules.dangerous -> Level.DANGEROUS
            score >= rules.suspicious -> Level.SUSPICIOUS
            else -> Level.SAFE
        }
        return VoiceScore(score, level, found)
    }

    private fun matches(c: VoiceRules.Category, text: String): Boolean {
        // OTP / PIN / CVV counts only when someone ASKS for it ("tell me the OTP", "OTP batao"),
        // not when a genuine bank mentions it ("you'll get an OTP, enter it in the app") or warns
        val credential = c.id == "credential"
        fun counts(at: Int) = !credential || (!negated(text, at) && asked(text, at))
        for (rx in c.latin) {
            for (m in rx.findAll(text)) if (counts(m.range.first)) return true
        }
        for (p in c.devanagari) {
            var i = text.indexOf(p)
            while (i >= 0) {
                if (counts(i)) return true
                i = text.indexOf(p, i + 1)
            }
        }
        return false
    }

    /** An asking verb within a few words of the credential word. */
    private fun asked(text: String, at: Int): Boolean =
        ASK.containsMatchIn(text.substring(maxOf(0, at - 40), minOf(text.length, at + 45)))

    /** "never share your OTP", "OTP kisi ko mat batana": a warning, not a request. */
    private fun negated(text: String, at: Int): Boolean {
        val before = text.substring(maxOf(0, at - 25), at)
        val after = text.substring(at, minOf(text.length, at + 30))
        return NEG_BEFORE.containsMatchIn(before) || NEG_AFTER.containsMatchIn(after)
    }

    companion object {
        private val NEG_BEFORE = Regex("(?<![a-z])(never|don't|dont|do not|not to)(?![a-z])|मत|कभी")
        private val ASK = Regex(
            "(?<![a-z])(tell|say|share|give|send|read|confirm|forward|provide|need|want|batao|bataiye|bataye|bata|bolo|boliye|bol do|dijiye|de do|do na|bhejo|chahiye)(?![a-z])" +
                "|बताओ|बताइए|बताइये|बोलिए|बोलो|दीजिए|भेजो|चाहिए")
        private val NEG_AFTER =Regex("(?<![a-z])(mat|nahi|na) (batana|batao|bataiye|batayein|share|dena|dijiye)(?![a-z])|मत बताना|न बताएं")

        /** Lower-case, punctuation to spaces ("o.t.p" -> "o t p"), single spaces; Devanagari kept. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s.lowercase()) {
                sb.append(if (c.isLetterOrDigit() || c == '\'' || c in 'ऀ'..'ॿ') c else ' ')
            }
            return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}

/** What a context model (the on-device LLM) said about the window. */
data class Judgement(val level: Level, val reason: String)

/**
 * One guarded call: a rolling transcript window, the rule score, an optional LLM opinion that can
 * only raise the level, and hysteresis (once DANGEROUS, the rest of the call stays DANGEROUS).
 */
class CallSession(private val rules: VoiceRules, private val now: () -> Long = System::currentTimeMillis) {
    private val scorer = VoiceScorer(rules)
    private val finals = ArrayDeque<Pair<Long, String>>()
    private var partial = ""
    var llm: Judgement? = null
        private set
    var latched: Level = Level.SAFE
        private set
    var maxScore = 0
        private set
    val seen = LinkedHashSet<String>()  // every category heard during the call
    val transcriptLog = mutableListOf<String>()

    fun addFinal(text: String) {
        if (text.isBlank()) return
        finals.addLast(now() to text)
        transcriptLog += text
        partial = ""
    }

    fun setPartial(text: String) { partial = text }

    fun windowText(): String {
        val cutoff = now() - rules.windowMs
        while (finals.isNotEmpty() && finals.first().first < cutoff) finals.removeFirst()
        return (finals.map { it.second } + partial).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Raise-only: the LLM can make things riskier, never safer. */
    fun applyJudgement(j: Judgement) {
        if (llm == null || j.level > llm!!.level) llm = j
    }

    fun update(): VoiceScore {
        val s = scorer.score(windowText())
        seen += s.categories
        maxScore = maxOf(maxScore, s.score)
        var level = s.level
        llm?.let { j ->
            // the AI alone reaches at most amber; it can lift amber (rules already heard warning
            // signs) to red, but a call with no rule signal never goes red on the AI's word
            val cap = if (s.level >= Level.SUSPICIOUS) Level.DANGEROUS else Level.SUSPICIOUS
            val ai = if (j.level > cap) cap else j.level
            if (ai > level) level = ai
        }
        if (level > latched) latched = level
        if (latched == Level.DANGEROUS) level = Level.DANGEROUS  // no flicker once red
        return s.copy(level = level)
    }
}
