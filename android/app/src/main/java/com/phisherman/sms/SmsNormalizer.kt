package com.phisherman.sms

/** Kotlin twin of ml/normalize.py. Same regexes (ASCII classes only), same order. */
object SmsNormalizer {
    const val VERSION = "sms-norm-2"

    private val URL_RE = Regex(
        "(?:https?://|www\\.)[^ \\t\\r\\n]+" +
            "|(?<![a-z0-9@.-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|net|org|co|ly|xyz|top|info|link|site|online|app|me|io|example|sbi|gov\\.in|co\\.in)(?:/[^ \\t\\r\\n]*)?(?![a-z0-9])"
    )
    private val LONG_NUM_RE = Regex("(?<![0-9])[0-9]{9,}(?![0-9])")
    private val OTP_RE = Regex("(?<![0-9a-z])[0-9]{4,8}(?![0-9a-z])")
    private val WORD_RE = Regex("[a-z0-9]+")

    // same insertion order as Python's VARIANTS dict, then sorted longest key first (stable)
    private val VARIANTS = listOf(
        "k.y.c" to "kyc", "e-kyc" to "ekyc", "aadhar" to "aadhaar", "adhaar" to "aadhaar", "adhar" to "aadhaar",
        "blocked" to "block", "band" to "block", "bandh" to "block", "suspended" to "suspend",
        "turant" to "jaldi", "fauran" to "jaldi", "immediately" to "urgent", "urgently" to "urgent",
        "acount" to "account", "a/c" to "account", "acc" to "account", "khaata" to "khata",
        "plz" to "please", "pls" to "please", "bhejiye" to "bhejo", "bhejein" to "bhejo", "bhej" to "bhejo",
        "batayein" to "batao", "bataye" to "batao", "bataiye" to "batao", "bataen" to "batao",
    ).sortedByDescending { it.first.length }
        .map { (k, v) -> Regex("(?<![a-z0-9])" + Regex.escape(k) + "(?![a-z0-9])") to v }

    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't')

    data class Result(val text: String, val urls: List<String>)

    fun normalize(text: String): Result {
        var s = stripAstral(text)
        s = collapseWs(s.lowercase())
        val urls = URL_RE.findAll(s).map { it.value }.toList()
        s = URL_RE.replace(s, " ")  // links are judged by the link engine only, never by the text model
        s = LONG_NUM_RE.replace(s, " numtoken ")
        s = OTP_RE.replace(s, " otpcode ")
        s = WORD_RE.replace(s) { deleet(it.value) }
        for ((rx, v) in VARIANTS) s = rx.replace(s, v)
        return Result(collapseWs(s), urls)
    }

    private fun deleet(w: String): String {
        val letters = w.count { it in 'a'..'z' }
        if (w.length >= 3 && letters > w.length - letters && w.all { it in 'a'..'z' || it in LEET }) {
            return w.map { LEET[it] ?: it }.joinToString("")
        }
        return w
    }

    /** Python: " ".join(s.split()) */
    fun collapseWs(s: String): String = splitWs(s).joinToString(" ")

    fun splitWs(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in s) {
            if (c.isWhitespace()) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            } else sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Characters above U+FFFF (emoji) become one space each, like ord(c) > 0xFFFF in Python. */
    private fun stripAstral(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                sb.append(' ')
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
