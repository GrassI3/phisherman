package com.phisherman.sms

import android.content.Context
import android.util.Log
import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Reason
import com.phisherman.engine.Verdict
import com.phisherman.link.LinkAnalyser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads the SMS model once and scores messages (received or pasted) off the main thread. */
object SmsAnalyser {
    private const val TAG = "SmsAnalyser"
    private val mutex = Mutex()
    @Volatile private var decider: SmsDecider? = null
    @Volatile var loadError: String? = null
        private set

    suspend fun init(ctx: Context) {
        LinkAnalyser.init(ctx)
        if (decider != null || loadError != null) return
        mutex.withLock {
            if (decider != null || loadError != null) return
            withContext(Dispatchers.Default) {
                try {
                    val app = ctx.applicationContext
                    fun text(n: String) = app.assets.open(n).bufferedReader().use { it.readText() }
                    val cfg = LinkAnalyser.config() ?: error("link engine failed: ${LinkAnalyser.loadError}")
                    val th = Json.parseToJsonElement(text("thresholds.json")).jsonObject["sms"]!!.jsonObject
                    decider = SmsDecider(SmsModel(text("sms_model.json")), cfg,
                        SmsThresholds(th["suspicious"]!!.jsonPrimitive.double, th["dangerous"]!!.jsonPrimitive.double),
                        LinkAnalyser::decideNow)
                } catch (e: Throwable) {
                    loadError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.e(TAG, "sms assets failed to load", e)
                }
            }
        }
    }

    suspend fun analyse(ctx: Context, sender: String?, body: String, trustedSender: Boolean = false): Verdict {
        init(ctx)
        val d = decider ?: return failOpen(sender)
        return withContext(Dispatchers.Default) {
            try {
                d.decide(sender, body, trustedSender).verdict
            } catch (e: Throwable) {
                Log.e(TAG, "sms scoring failed", e)
                failOpen(sender)
            }
        }
    }

    private fun failOpen(sender: String?) = Verdict(
        Channel.SMS, 50, Level.SUSPICIOUS,
        listOf(Reason("engine_error", "Phisherman could not check this message, so be careful",
            "Phisherman इस संदेश की जाँच नहीं कर सका, सावधान रहें")),
        null, listOf("engine_error"), 0, sender ?: "message")
}
