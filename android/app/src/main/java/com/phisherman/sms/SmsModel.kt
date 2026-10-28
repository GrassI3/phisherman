package com.phisherman.sms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * TF-IDF (word 1-2 grams + char_wb 3-5 grams, sublinear tf, L2 per block) + logistic regression,
 * exported by ml/train_sms.py. Mirrors SmsEngine.prob in ml/sms_engine.py.
 */
class SmsModel(json: String) {
    private class Block(val vocab: HashMap<String, Int>, val idf: DoubleArray, val coef: DoubleArray, val lo: Int, val hi: Int)

    private val root = Json.parseToJsonElement(json).jsonObject
    val version: String = root["version"]!!.jsonPrimitive.content
    private val word = block(root["word"]!!.jsonObject)
    private val char = block(root["char"]!!.jsonObject)
    private val intercept = root["intercept"]!!.jsonPrimitive.double

    init {
        val norm = root["normalizer"]!!.jsonPrimitive.content
        check(norm == SmsNormalizer.VERSION) { "sms_model.json uses normaliser $norm, app has ${SmsNormalizer.VERSION}" }
    }

    private fun block(o: JsonObject): Block {
        val vocab = HashMap<String, Int>(o["vocab"]!!.jsonObject.size * 2)
        for ((k, v) in o["vocab"]!!.jsonObject) vocab[k] = v.jsonPrimitive.int
        val ng = o["ngram"]!!.jsonArray.map { it.jsonPrimitive.int }
        return Block(vocab, o["idf"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            o["coef"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(), ng[0], ng[1])
    }

    fun prob(norm: String): Double {
        var z = intercept
        z += blockScore(word, wordNgrams(norm, word.lo, word.hi))
        z += blockScore(char, charWbNgrams(norm, char.lo, char.hi))
        return 1.0 / (1.0 + exp(-z))
    }

    private fun blockScore(b: Block, grams: List<String>): Double {
        val tf = LinkedHashMap<Int, Int>()
        for (g in grams) b.vocab[g]?.let { tf[it] = (tf[it] ?: 0) + 1 }
        val vals = tf.entries.associate { (j, c) -> j to (1.0 + ln(c.toDouble())) * b.idf[j] }
        val norm = sqrt(vals.values.sumOf { it * it }).let { if (it == 0.0) 1.0 else it }
        return vals.entries.sumOf { (j, v) -> b.coef[j] * v / norm }
    }

    companion object {
        /** sklearn token_pattern [\wऀ-ॿ]{2,}: maximal runs of word chars, length >= 2. */
        fun tokens(text: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (c in text) {
                if (c.isLetterOrDigit() || c == '_' || c in 'ऀ'..'ॿ') sb.append(c) else {
                    if (sb.length >= 2) out += sb.toString()
                    sb.clear()
                }
            }
            if (sb.length >= 2) out += sb.toString()
            return out
        }

        fun wordNgrams(text: String, lo: Int, hi: Int): List<String> {
            val toks = tokens(text)
            val out = mutableListOf<String>()
            for (n in lo..hi) for (i in 0..toks.size - n) out += toks.subList(i, i + n).joinToString(" ")
            return out
        }

        /** sklearn _char_wb_ngrams, including its early break for short words. */
        fun charWbNgrams(text: String, lo: Int, hi: Int): List<String> {
            val out = mutableListOf<String>()
            for (raw in SmsNormalizer.splitWs(text)) {
                val w = " $raw "
                for (n in lo..hi) {
                    var offset = 0
                    out += w.substring(offset, minOf(offset + n, w.length))
                    while (offset + n < w.length) {
                        offset++
                        out += w.substring(offset, offset + n)
                    }
                    if (offset == 0) break
                }
            }
            return out
        }
    }
}
