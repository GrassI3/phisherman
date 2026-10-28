package com.phisherman.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.phisherman.voice.ContextJudge
import com.phisherman.voice.Judgement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The fine-tuned Gemma 3 270M scam checker, running on the phone through MediaPipe (offline).
 * A second opinion for chats and calls: callers only ever use it to RAISE a risk level.
 * Prompt format must match ml/llm_data.py exactly (it was trained on it).
 */
object ScamLlm : ContextJudge {
    private const val TAG = "ScamLlm"
    private const val ASSET = "phisherman_scam.task"
    private const val MAX_TEXT = 600
    private val ANSWER = Regex("(SAFE|SUSPICIOUS|SCAM)\\s*\\|\\s*([a-z_]+)")
    private val URL = Regex("(?i)(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|net|org|co|ly|xyz|top|info|link|site|online|app|me|io|example|sbi)(?:/\\S*)?")

    private val mutex = Mutex()
    @Volatile private var llm: LlmInference? = null
    @Volatile var loadError: String? = null
        private set
    @Volatile var lastLatencyMs: Long = 0
        private set

    fun available(ctx: Context): Boolean = llm != null || assetExists(ctx)

    private fun assetExists(ctx: Context) = runCatching { ctx.assets.openFd(ASSET).close(); true }.getOrDefault(false)

    /** Copies the model out of the APK once (MediaPipe needs a file path) and loads it. */
    suspend fun init(ctx: Context): Boolean = mutex.withLock {
        if (llm != null) return@withLock true
        if (loadError != null) return@withLock false
        withContext(Dispatchers.IO) {
            try {
                val file = File(ctx.filesDir, ASSET)
                val size = ctx.assets.openFd(ASSET).use { it.length }
                if (!file.exists() || file.length() != size) {
                    ctx.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
                }
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(512)
                    .build()
                llm = LlmInference.createFromOptions(ctx, opts)
                Log.i(TAG, "loaded (${file.length() / 1_000_000} MB)")
                true
            } catch (e: Throwable) {
                loadError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "on-device LLM not available: $loadError")
                false
            }
        }
    }

    fun prompt(source: String, sender: String, text: String): String {
        val body = URL.replace(text.replace(" || ", "\n"), " ").replace(Regex("[ \\t]+"), " ").trim().take(MAX_TEXT)
        return "<start_of_turn>user\nScam check. Source: ${source.ifBlank { "unknown" }}. From: ${sender.ifBlank { "unknown" }}.\n" +
            "$body\nReply: LABEL | reason<end_of_turn>\n<start_of_turn>model\n"
    }

    /** Returns null when the model is not loaded or its answer cannot be parsed. */
    override suspend fun judge(source: String, sender: String, text: String): Judgement? {
        val engine = llm ?: return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val t0 = System.nanoTime()
                val session = LlmInferenceSession.createFromOptions(engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build())
                try {
                    session.addQueryChunk(prompt(source, sender, text))
                    val out = session.generateResponse()
                    lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
                    val m = ANSWER.find(out) ?: return@withContext null.also { Log.d(TAG, "unparsed: $out") }
                    val level = when (m.groupValues[1]) { "SCAM" -> Level.DANGEROUS; "SUSPICIOUS" -> Level.SUSPICIOUS; else -> Level.SAFE }
                    Log.d(TAG, "${m.value} in ${lastLatencyMs} ms")
                    Judgement(level, m.groupValues[2])
                } catch (e: Throwable) {
                    Log.w(TAG, "inference failed", e)
                    null
                } finally {
                    session.close()
                }
            }
        }
    }

    /** Plain-language text for a reason code, English / Hindi. */
    fun reasonText(code: String): Pair<String, String> = when (code) {
        "credential_request" -> "On-device AI: asks for an OTP, PIN, password or card details" to "ऑन-डिवाइस AI: OTP, PIN या कार्ड की जानकारी माँग रहा है"
        "payment_request" -> "On-device AI: pressures you to pay or transfer money" to "ऑन-डिवाइस AI: पैसे भेजने का दबाव"
        "authority_impersonation" -> "On-device AI: pretends to be a bank, police, boss or official" to "ऑन-डिवाइस AI: बैंक, पुलिस या अधिकारी होने का दिखावा"
        "family_impersonation" -> "On-device AI: pretends to be family or a friend with a new number" to "ऑन-डिवाइस AI: नए नंबर से परिवार/दोस्त होने का दिखावा"
        "threat_urgency" -> "On-device AI: threatens or rushes you" to "ऑन-डिवाइस AI: धमकी या जल्दबाज़ी"
        "prize_lottery" -> "On-device AI: fake prize, lottery or reward" to "ऑन-डिवाइस AI: नकली इनाम या लॉटरी"
        "job_investment" -> "On-device AI: too-good-to-be-true job or investment" to "ऑन-डिवाइस AI: झूठी नौकरी या निवेश"
        "remote_access" -> "On-device AI: wants you to install a remote-control app" to "ऑन-डिवाइस AI: रिमोट ऐप इंस्टॉल करवाना चाहता है"
        "secrecy" -> "On-device AI: asks you to keep it secret" to "ऑन-डिवाइस AI: बात छिपाने को कह रहा है"
        else -> "On-device AI: this conversation looks like a scam" to "ऑन-डिवाइस AI: यह बातचीत धोखाधड़ी जैसी लगती है"
    }
}
