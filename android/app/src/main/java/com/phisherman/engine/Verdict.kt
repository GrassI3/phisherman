package com.phisherman.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Channel { LINK, SMS, VOICE }

@Serializable
enum class Level { SAFE, SUSPICIOUS, DANGEROUS }

@Serializable
data class Reason(val id: String, val textEn: String, val textHi: String?)

/** Every analyser returns this; the warning screen, log and metrics are written once against it. */
@Serializable
data class Verdict(
    val channel: Channel,
    val score: Int,               // 0..100, higher = riskier
    val level: Level,
    val reasons: List<Reason>,    // 1..4 user-readable reasons, strongest first
    val modelProb: Float?,        // raw model probability if a model was used
    val ruleHits: List<String>,   // ids of rules that fired (log and debugging)
    val latencyMs: Long,
    val subject: String = "",     // host for links, sender for SMS, "call" for voice
)
