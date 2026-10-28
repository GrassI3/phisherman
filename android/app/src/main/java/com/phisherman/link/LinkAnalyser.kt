package com.phisherman.link

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Reason
import com.phisherman.engine.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Loads link assets once, scores URLs on a background thread, caches recent verdicts. */
object LinkAnalyser {
    private const val TAG = "LinkAnalyser"
    private const val CACHE_SIZE = 300
    private const val OPEN_ANYWAY_MS = 10 * 60 * 1000L

    private val mutex = Mutex()
    @Volatile private var decider: LinkDecider? = null
    @Volatile private var config: LinkConfig? = null
    @Volatile var loadError: String? = null
        private set

    private val cache = object : LinkedHashMap<String, Verdict>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict>?) = size > CACHE_SIZE
    }
    private val openedAnyway = HashMap<String, Long>()

    suspend fun init(ctx: Context) {
        if (decider != null || loadError != null) return
        mutex.withLock {
            if (decider != null || loadError != null) return
            withContext(Dispatchers.Default) {
                try {
                    decider = load(ctx.applicationContext)
                } catch (e: Throwable) {
                    loadError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.e(TAG, "link assets failed to load", e)
                }
            }
        }
    }

    private fun load(ctx: Context): LinkDecider {
        fun text(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }
        val cfg = LinkConfig(text("feature_order.json"), text("brands.json"))
        config = cfg
        val allow = Json.parseToJsonElement(text("allowlist.json")).jsonObject["domains"]!!.jsonArray
            .map { it.jsonPrimitive.content }.toSet() + cfg.official
        val meta = Json.parseToJsonElement(text("blocklist.json")).jsonObject
        val blocklist = Blocklist(ctx.assets.open("blocklist.bloom").use { it.readBytes() },
            meta["m_bits"]!!.jsonPrimitive.long, meta["k"]!!.jsonPrimitive.long.toInt())
        val th = Json.parseToJsonElement(text("thresholds.json")).jsonObject["link"]!!.jsonObject
        val thresholds = LinkThresholds(th["suspicious"]!!.jsonPrimitive.float, th["dangerous"]!!.jsonPrimitive.float)

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(ctx.assets.open("link_model.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
        val scorer = LinkScorer { f ->
            OnnxTensor.createTensor(env, arrayOf(f)).use { input ->
                session.run(mapOf("input" to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    (out.get("probabilities").get().value as Array<FloatArray>)[0][1]
                }
            }
        }
        return LinkDecider(cfg, allow, blocklist, thresholds, scorer)
    }

    /** Null means "not a web link" (the router ignores it). Never throws. */
    suspend fun analyse(ctx: Context, url: String): Verdict? {
        init(ctx)
        val key = url.trim()
        synchronized(cache) { cache[key] }?.let { return it }
        val d = decider ?: return failOpen(key)
        val v = withContext(Dispatchers.Default) {
            try {
                d.decide(key)
            } catch (e: Throwable) {
                Log.e(TAG, "scoring failed", e)
                failOpen(key)
            }
        } ?: return null
        synchronized(cache) { cache[key] = v }
        return v
    }

    /** Synchronous scoring for callers already off the main thread (SMS engine). Call init first. */
    fun decideNow(url: String): Verdict? {
        val key = url.trim()
        synchronized(cache) { cache[key] }?.let { return it }
        val d = decider ?: return failOpen(key)
        val v = try { d.decide(key) } catch (e: Throwable) { failOpen(key) } ?: return null
        synchronized(cache) { cache[key] = v }
        return v
    }

    /** Shared config (brands, suffix lists) for other analysers. Null until init succeeds. */
    fun config(): LinkConfig? = config

    /** A broken model must never silently pass a link: warn with a clear reason instead. */
    private fun failOpen(url: String) = Verdict(
        Channel.LINK, 50, Level.SUSPICIOUS,
        listOf(Reason("engine_error", "Phisherman could not check this link, so be careful",
            "Phisherman इस लिंक की जाँच नहीं कर सका, सावधान रहें")),
        null, listOf("engine_error"), 0, url.take(80))

    /** Part of the host a user should read as "the real website" (example.co.in). */
    fun registrableDomain(host: String): String {
        if (host.isEmpty() || ':' in host || host.all { it.isDigit() || it == '.' }) return host
        val c = config ?: return host.split('.').takeLast(2).joinToString(".")
        return UrlFeatures.splitHost(host, c).first
    }

    fun markOpenedAnyway(url: String) = synchronized(openedAnyway) { openedAnyway[url.trim()] = System.currentTimeMillis() }

    /** User already chose "Open anyway" for this exact URL in the last few minutes. */
    fun recentlyOpenedAnyway(url: String): Boolean = synchronized(openedAnyway) {
        val t = openedAnyway[url.trim()] ?: return false
        System.currentTimeMillis() - t < OPEN_ANYWAY_MS
    }
}
