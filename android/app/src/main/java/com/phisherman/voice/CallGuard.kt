package com.phisherman.voice

import android.content.Context
import com.phisherman.data.DecisionLog
import com.phisherman.engine.Channel
import com.phisherman.engine.Level
import com.phisherman.engine.Reason
import com.phisherman.engine.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Optional context model (the on-device LLM). It can only raise the level, never lower it. */
fun interface ContextJudge {
    suspend fun judge(source: String, sender: String, text: String): Judgement?
}

enum class GuardMode { OFF, MIC, SCRIPT }

data class GuardState(
    val mode: GuardMode = GuardMode.OFF,
    val level: Level = Level.SAFE,
    val score: Int = 0,
    val heard: List<String> = emptyList(),   // category ids heard in the call, in order
    val lines: List<String> = emptyList(),   // last few transcript lines
    val partial: String = "",
    val llmReason: String? = null,
    val alerting: Boolean = false,
    val error: String? = null,
    val lastSummary: Verdict? = null,        // set when a guarded call ends
    val peakDb: Float = -100f,               // loudest mic input heard; stays low if Android silences the mic
)

/** Live state of the call guard, shared by the service (mic), the demo-script player and the UI. */
object CallGuard {
    private const val LLM_EVERY_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state

    @Volatile var judge: ContextJudge? = null
    private var rules: VoiceRules? = null
    private var session: CallSession? = null
    private var lastLlmAt = 0L
    private var llmJob: Job? = null

    fun rules(ctx: Context): VoiceRules = rules ?: VoiceRules(
        ctx.assets.open("voice_rules.json").bufferedReader().use { it.readText() }).also { rules = it }

    @Synchronized
    fun begin(ctx: Context, mode: GuardMode) {
        if (_state.value.mode != GuardMode.OFF) end(ctx)
        session = CallSession(rules(ctx))
        lastLlmAt = 0L
        _state.value = GuardState(mode = mode, lastSummary = null)
    }

    @Synchronized
    fun onPartial(ctx: Context, text: String) {
        val s = session ?: return
        s.setPartial(text)
        publish(ctx, s, partial = text)
    }

    @Synchronized
    fun onFinal(ctx: Context, text: String) {
        val s = session ?: return
        s.addFinal(text)
        publish(ctx, s, partial = "")
        maybeAskLlm(ctx, s)
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    /** Microphone level from the recogniser (dB). Tracks the loudest input so silence can be shown. */
    fun onLevel(rmsDb: Float) {
        val s = _state.value
        if (rmsDb > s.peakDb) _state.value = s.copy(peakDb = rmsDb)
    }

    private fun publish(ctx: Context, s: CallSession, partial: String) {
        val before = _state.value.level
        val score = s.update()
        val r = rules(ctx)
        _state.value = _state.value.copy(
            level = score.level, score = maxOf(score.score, if (score.level == Level.DANGEROUS) r.dangerous else 0),
            heard = s.seen.toList(), lines = s.transcriptLog.takeLast(6), partial = partial,
            llmReason = s.llm?.reason, error = null,
        )
        if (score.level > before) {
            when (score.level) {
                Level.SUSPICIOUS -> CallAlerts.amber(ctx)
                Level.DANGEROUS -> {
                    CallAlerts.red(ctx)
                    CallGuardNotifier.red(ctx, reasonsFor(ctx, s.seen.toList()).firstOrNull()?.textEn)
                }
                else -> {}
            }
            CallGuardNotifier.updateOngoing(ctx, score.level)
        }
        _state.value = _state.value.copy(alerting = CallAlerts.redActive)
    }

    /** Every ~10 s, give the transcript window to the LLM (if loaded) for a raise-only opinion. */
    private fun maybeAskLlm(ctx: Context, s: CallSession) {
        val j = judge ?: return
        val now = System.currentTimeMillis()
        if (now - lastLlmAt < LLM_EVERY_MS || llmJob?.isActive == true) return
        lastLlmAt = now
        val text = s.windowText()
        llmJob = scope.launch {
            val verdict = runCatching { j.judge("Call", "Unknown caller", text) }.getOrNull() ?: return@launch
            synchronized(this@CallGuard) {
                if (session !== s) return@launch
                s.applyJudgement(verdict)
                publish(ctx, s, _state.value.partial)
            }
        }
    }

    fun acknowledge(ctx: Context) {
        CallAlerts.stop(ctx)
        CallGuardNotifier.cancelRed(ctx)
        _state.value = _state.value.copy(alerting = false)
    }

    /** Ends the guarded call: stops alerts and logs one VOICE verdict (no audio, no transcript). */
    @Synchronized
    fun end(ctx: Context) {
        val s = session ?: return
        session = null
        llmJob?.cancel()
        CallAlerts.stop(ctx)
        CallGuardNotifier.cancelRed(ctx)
        val level = s.latched.coerceAtLeast(s.llm?.level ?: Level.SAFE)
        val reasons = reasonsFor(ctx, s.seen.toList())
        val v = Verdict(Channel.VOICE, s.maxScore, level, reasons.take(4), null,
            s.seen.toList() + listOfNotNull(s.llm?.let { "llm:${it.reason}" }), 0, "call")
        if (s.transcriptLog.isNotEmpty()) DecisionLog.recordAsync(ctx, v)
        android.util.Log.i("CallGuard", "session ended: level=$level lines=${s.transcriptLog.size} peakDb=${_state.value.peakDb}")
        _state.value = GuardState(lastSummary = v, peakDb = _state.value.peakDb)
    }

    fun reasonsFor(ctx: Context, ids: List<String>): List<Reason> {
        val r = rules(ctx)
        return ids.mapNotNull { id -> r.categories.firstOrNull { it.id == id } }
            .sortedByDescending { it.weight }.map { Reason(it.id, it.en, it.hi) }
    }
}
