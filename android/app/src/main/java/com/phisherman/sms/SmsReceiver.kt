package com.phisherman.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.phisherman.data.DecisionLog
import com.phisherman.engine.Level
import com.phisherman.engine.Reason
import com.phisherman.engine.ScamLlm
import com.phisherman.engine.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Scores every incoming SMS right after it arrives. It never blocks or hides the message. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        val sender = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }  // multipart messages arrive in pieces
        SmsHandler.handle(c, sender, body, goAsync())
    }
}

object SmsHandler {
    private const val TAG = "SmsHandler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val DEDUP_MS = 60_000L
    private val recent = LinkedHashMap<Int, Long>()

    /**
     * Shared by the SMS receiver, the notification listener (RCS / chat apps) and the debug receiver.
     * sourceApp is set for messages read from another app's notification.
     */
    fun handle(
        ctx: Context, sender: String, body: String, pending: BroadcastReceiver.PendingResult?,
        sourceApp: String? = null,
        recordSafe: Boolean = true,  // false for non-chat apps: only flagged notifications are logged
        forceNew: Boolean = false,   // the caller knows this is a new copy of an earlier text
        context: String? = null,     // recent messages of the same chat, for the on-device AI
    ) {
        val app = ctx.applicationContext
        // the same message can arrive twice: dual SIM, or SMS broadcast + Messages notification
        if (!forceNew && isDuplicate(body)) { Log.d(TAG, "duplicate dropped"); pending?.finish(); return }
        scope.launch {
            try {
                val trusted = isTrustedName(sender, sourceApp)
                var v = SmsAnalyser.analyse(app, sender, body, trusted)
                v = secondOpinion(v, sender, body, context, sourceApp, trusted)
                if (sourceApp != null) v = v.copy(subject = "$sender · $sourceApp")
                Log.d(TAG, "verdict ${v.level} ${v.ruleHits} from ${sourceApp ?: "SMS"}")
                if (recordSafe || v.level != Level.SAFE) DecisionLog.record(app, v)
                if (v.level != Level.SAFE) {
                    SmsNotifier.show(app, v.subject, body, v)
                    // the phone may shrink notifications to an edge flash; draw our own banner too
                    ChatScreenReader.instance?.showBanner(body, v)
                }
            } catch (e: Exception) {
                Log.e(TAG, "message check failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private val HEADER = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(?:-[A-Z])?$")

    /**
     * The on-device LLM reads the message (or the recent chat) and may RAISE the level, never
     * lower it. Skipped for trusted senders (bank headers, saved / verified names in SMS apps)
     * and when the rules already said DANGEROUS.
     */
    private suspend fun secondOpinion(v: Verdict, sender: String, body: String, context: String?, sourceApp: String?, trusted: Boolean): Verdict {
        if (v.level == Level.DANGEROUS || trusted || HEADER.matches(sender.trim()) || "R_ALLOW_SENDER" in v.ruleHits) return v
        val j = ScamLlm.judge(sourceApp ?: "SMS", sender, context ?: body) ?: return v
        if (j.level <= v.level) return v
        val (en, hi) = ScamLlm.reasonText(j.reason)
        Log.d(TAG, "on-device AI raised ${v.level} -> ${j.level} (${j.reason})")
        return v.copy(level = j.level, score = maxOf(v.score, if (j.level == Level.DANGEROUS) 90 else 60),
            reasons = (listOf(Reason("llm_${j.reason}", en, hi)) + v.reasons).take(3),
            ruleHits = v.ruleHits + "llm:${j.reason}")
    }

    /**
     * In the SMS apps a sender shown as a name (not digits) is a saved contact or a business that
     * Google / Samsung verified, which a scammer cannot fake. WhatsApp names are not trusted:
     * hijacked friend accounts are a common scam route.
     */
    private val SMS_APPS = setOf("Messages", "Samsung Messages")
    fun isTrustedName(sender: String, sourceApp: String?): Boolean =
        sourceApp in SMS_APPS && sender.any { it.isLetter() } && sender.count { it.isDigit() } < 6

    private fun isDuplicate(body: String): Boolean = synchronized(recent) {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > DEDUP_MS }
        val key = body.trim().hashCode()
        if (key in recent) return true
        recent[key] = now
        false
    }
}
