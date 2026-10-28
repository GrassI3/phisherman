package com.phisherman.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.log2

/** Lists shared with ml/features.py, loaded from feature_order.json and brands.json. */
class LinkConfig(featureOrderJson: String, brandsJson: String) {
    private val fo = Json.parseToJsonElement(featureOrderJson).jsonObject
    private val br = Json.parseToJsonElement(brandsJson).jsonObject

    val version: String = fo.str("version")
    val featureNames: List<String> = fo.list("features")
    val multi: Set<String> = fo.list("multi_suffixes").toSet()
    val shorteners: Set<String> = fo.list("shorteners").toSet()
    val suspWords: List<String> = fo.list("susp_words")
    val riskyTlds: Set<String> = fo.list("risky_tlds").toSet()
    val freeHosts: Set<String> = fo.list("free_hosts").toSet()
    val maxUrlLen: Int = fo["max_url_len"]!!.jsonPrimitive.int

    val brandTokens: List<String> = br.list("tokens")
    val official: Set<String> = br.list("official").toSet()
    val brandNames: Map<String, String> = br["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    val brandDomains: Map<String, List<String>> =
        br["brand_domains"]!!.jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content } }
    /** Insertion order kept; applied longest key first, like Python's stable sort. */
    val homoglyphs: List<Pair<String, String>> =
        br["homoglyphs"]!!.jsonObject.entries.map { it.key to it.value.jsonPrimitive.content }
            .sortedByDescending { it.first.length }

    /** Brand tokens plus the first label of each official domain, sorted (link_engine.brand_names). */
    val brandHostNames: List<String> = (brandTokens + official.map { it.substringBefore('.') }).toSortedSet().toList()

    init {
        check(featureNames.size == UrlFeatures.SIZE) { "feature_order.json has ${featureNames.size} features, app expects ${UrlFeatures.SIZE}" }
        check(version == UrlFeatures.VERSION) { "feature_order.json is $version, app expects ${UrlFeatures.VERSION}" }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
}

/** Canonical parse result, the Kotlin twin of features.parse(). */
data class ParsedUrl(
    val split: UrlParser.Split,
    val host: String,
    val isIp: Boolean,
    val reg: String,
    val sub: List<String>,
)

object UrlFeatures {
    const val VERSION = "link-features-2"
    const val SIZE = 19
    const val I_BRAND_MIN_LEV = 15
    private const val SPECIALS = "@?=&%_"
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun splitHost(host: String, cfg: LinkConfig): Pair<String, List<String>> {
        val labels = host.split(".")
        val n = if (labels.size >= 3 && labels.takeLast(2).joinToString(".") in cfg.multi) 3 else 2
        if (labels.size < n) return host to emptyList()
        return labels.takeLast(n).joinToString(".") to labels.dropLast(n)
    }

    fun parse(url: String, cfg: LinkConfig): ParsedUrl {
        var u = url.trim().take(cfg.maxUrlLen)
        if ("://" !in u) u = "http://$u"
        var split = UrlParser.urlsplit(u)
        var host = ""
        if (split == null) {
            split = UrlParser.Split("http", "invalid", "", "")
        } else {
            host = (UrlParser.hostname(split.netloc) ?: "").trimEnd('.')
        }
        val isIp = IPV4.matches(host) || ':' in host
        val (reg, sub) = if (isIp || host.isEmpty()) "" to emptyList() else splitHost(host, cfg)
        return ParsedUrl(split, host, isIp, reg, sub)
    }

    fun registrableDomain(url: String, cfg: LinkConfig): String = parse(url, cfg).let { if (it.isIp) it.host else it.reg }

    fun isFreeHost(host: String, reg: String, cfg: LinkConfig): Boolean =
        reg in cfg.freeHosts || cfg.freeHosts.any { host == it || host.endsWith(".$it") }

    fun deglyph(s: String, cfg: LinkConfig): String {
        var out = s
        for ((k, v) in cfg.homoglyphs) out = out.replace(k, v)
        return out
    }

    fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = LinkedHashMap<Char, Int>()
        for (c in s) freq[c] = (freq[c] ?: 0) + 1
        val n = s.length.toDouble()
        return -freq.values.sumOf { v -> (v / n) * log2(v / n) }
    }

    fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Brand token closest to the registrable label, with its normalised distance. */
    fun closestBrand(reg: String, cfg: LinkConfig): Pair<String, Double>? {
        val label = deglyph(reg.substringBefore('.'), cfg)
        if (reg.isEmpty() || label.isEmpty()) return null
        return cfg.brandTokens.map { t -> t to lev(label, t).toDouble() / maxOf(label.length, t.length) }
            .minByOrNull { it.second }
    }

    /**
     * Brand name a lookalike host uses (sbi-kyc.example -> "sbi"), or null. Twin of
     * link_engine.brand_in_host: any host part equal to a brand name, or one edit away if 5+ chars.
     */
    fun brandInHost(host: String, reg: String, cfg: LinkConfig): String? {
        if (host.isEmpty() || reg in cfg.official) return null
        for (part in host.split('.', '-')) {
            val p = deglyph(part, cfg)
            for (n in cfg.brandHostNames) {
                if (p == n || (p.length >= 5 && lev(p, n) <= 1)) return n
            }
        }
        return null
    }

    fun compute(url: String, cfg: LinkConfig): FloatArray = compute(url, parse(url, cfg), cfg)

    fun compute(url: String, p: ParsedUrl, cfg: LinkConfig): FloatArray {
        val raw = url.trim().take(cfg.maxUrlLen)
        val host = p.host
        val path = UrlParser.unquote(p.split.path).lowercase()
        val low = host + path

        var levMin = 1.0
        closestBrand(p.reg, cfg)?.let { levMin = minOf(levMin, it.second) }
        val official = p.reg in cfg.official
        val inSub = cfg.brandTokens.any { it in host } && !official
        val tld = if (p.reg.isNotEmpty()) p.reg.substringAfterLast('.') else ""

        return floatArrayOf(
            raw.length.toFloat(),
            host.length.toFloat(),
            host.count { it == '.' }.toFloat(),
            p.sub.size.toFloat(),
            (raw.count { it.isDigit() }.toDouble() / maxOf(raw.length, 1)).toFloat(),
            host.count { it == '-' }.toFloat(),
            raw.count { it in SPECIALS }.toFloat(),
            if ('@' in p.split.netloc) 1f else 0f,
            if (p.isIp) 1f else 0f,
            if (p.split.scheme == "https") 1f else 0f,
            entropy(host).toFloat(),
            if (host in cfg.shorteners) 1f else 0f,
            cfg.suspWords.sumOf { low.countOf(it) }.toFloat(),
            p.split.path.length.toFloat(),
            p.split.query.split("&").count { it.isNotEmpty() }.toFloat(),
            levMin.toFloat(),
            if (inSub) 1f else 0f,
            if (tld in cfg.riskyTlds || "xn--" in host) 1f else 0f,
            if (isFreeHost(host, p.reg, cfg)) 1f else 0f,
        )
    }

    /** Non-overlapping count, like Python str.count. */
    private fun String.countOf(w: String): Int {
        var n = 0
        var i = indexOf(w)
        while (i >= 0) {
            n++
            i = indexOf(w, i + w.length)
        }
        return n
    }
}
