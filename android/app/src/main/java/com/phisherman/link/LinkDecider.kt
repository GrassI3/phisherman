package com.phisherman.link

import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Reason
import com.phisherman.engine.Verdict
import kotlin.math.roundToInt

fun interface LinkScorer {
    /** Probability that the URL is phishing, from the 19-feature vector. */
    fun prob(features: FloatArray): Float
}

data class LinkThresholds(val suspicious: Float, val dangerous: Float)

/**
 * Link decision logic; must give the same level and reason id as ml/link_engine.py.
 * Order: scheme -> allowlist -> blocklist -> shortener -> model -> brand floors (lookalike, brand in host).
 */
class LinkDecider(
    private val cfg: LinkConfig,
    private val allow: Set<String>,
    private val blocklist: Blocklist?,
    private val thresholds: LinkThresholds,
    private val scorer: LinkScorer,
) {
    /** Returns null for non-http(s) input, which the router ignores. */
    fun decide(url: String): Verdict? {
        val t0 = System.nanoTime()
        val s = url.trim()
        val firstSegment = s.substringBefore('/')
        val scheme = if (':' in firstSegment) s.substringBefore(':').lowercase() else ""
        if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") return null

        val p = UrlFeatures.parse(s, cfg)
        val subject = p.host.ifEmpty { s.take(80) }
        fun done(level: Level, score: Int, reasons: List<Reason>, hits: List<String>, prob: Float? = null) =
            Verdict(Channel.LINK, score, level, reasons.take(3), prob, hits, (System.nanoTime() - t0) / 1_000_000, subject)

        if (!p.isIp) {
            if (p.reg in allow && !UrlFeatures.isFreeHost(p.host, p.reg, cfg)) {
                return done(Level.SAFE, 0, listOf(LinkReasons.allowlisted(p.reg)), listOf("allowlist"))
            }
            if (blocklist != null && (Blocklist.normHost(p.host) in blocklist || (p.reg.isNotEmpty() && p.reg in blocklist))) {
                return done(Level.DANGEROUS, 100, listOf(LinkReasons.BLOCKLIST) + LinkReasons.fromUrl(p, null, cfg), listOf("blocklist"))
            }
        }
        if (p.host in cfg.shorteners) {
            return done(Level.SUSPICIOUS, 50, listOf(LinkReasons.SHORTENER), listOf("shortener"))
        }

        val f = UrlFeatures.compute(s, p, cfg)
        val prob = scorer.prob(f)
        var level = when {
            prob >= thresholds.dangerous -> Level.DANGEROUS
            prob >= thresholds.suspicious -> Level.SUSPICIOUS
            else -> Level.SAFE
        }
        var score = (prob * 100).roundToInt().coerceIn(0, 100)
        val hits = mutableListOf("model")
        if (level == Level.SAFE && f[UrlFeatures.I_BRAND_MIN_LEV] <= BRAND_LOOKALIKE_MAX_LEV && p.reg !in cfg.official) {
            level = Level.SUSPICIOUS
            score = maxOf(score, 50)
            hits[0] = "brand_lookalike"
        }
        if (level == Level.SAFE && UrlFeatures.brandInHost(p.host, p.reg, cfg) != null) {
            level = Level.SUSPICIOUS
            score = maxOf(score, 50)
            hits[0] = "brand_in_host"
        }
        val reasons = if (level == Level.SAFE) emptyList() else LinkReasons.fromUrl(p, f, cfg).ifEmpty { listOf(LinkReasons.MODEL) }
        return done(level, score, reasons, hits, prob)
    }

    companion object {
        const val BRAND_LOOKALIKE_MAX_LEV = 0.25f
    }
}
