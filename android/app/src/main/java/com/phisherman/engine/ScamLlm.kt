package com.phisherman.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.phisherman.voice.ContextJudge
import com.phisherman.voice.Judgement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The fine-tuned Gemma 3 270M scam checker (.litertlm), run on the phone by LiteRT-LM, offline.
 * A second opinion for chats, emails and calls: callers only ever use it to RAISE a risk level.
 * The runtime adds the Gemma turn template from the model file; user prompt below must match
 * user_prompt() in ml/llm_data.py exactly (it was trained on that text).
 */
object ScamLlm : ContextJudge {
    private const val TAG = "ScamLlm"
    private const val ASSET = "phisherman_scam.litertlm"
    private const val MAX_TEXT = 600
    private val ANSWER = Regex("(SAFE|SUSPICIOUS|SCAM)\\s*\\|\\s*([a-z_]+)")
    private val URL = Regex("(?i)(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|net|org|co|ly|xyz|top|info|link|site|online|app|me|io|example|sbi)(?:/\\S*)?")
    private val SPACES = Regex("[ \\t]+")

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile var loadError: String? = null
        private set
    @Volatile var lastLatencyMs: Long = 0
        private set

    fun available(ctx: Context): Boolean = engine != null ||
        runCatching { ctx.assets.openFd(ASSET).close(); true }.getOrDefault(false)

    /** Copies the model out of the APK once (the runtime needs a file path) and loads it. */
    suspend fun init(ctx: Context): Boolean = mutex.withLock {
        if (engine != null) return@withLock true
        if (loadError != null) return@withLock false
        withContext(Dispatchers.IO) {
            try {
                val t0 = System.nanoTime()
                val file = File(ctx.filesDir, ASSET)
                val size = ctx.assets.openFd(ASSET).use { it.length }
                if (!file.exists() || file.length() != size) {
                    ctx.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it, 1 shl 20) } }
                }
                val e = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = 512,
                    cacheDir = ctx.cacheDir.absolutePath))
                e.initialize()
                engine = e
                Log.i(TAG, "loaded ${file.length() / 1_000_000} MB in ${(System.nanoTime() - t0) / 1_000_000} ms")
                true
            } catch (t: Throwable) {
                loadError = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "on-device LLM not available: $loadError")
                false
            }
        }
    }

    /** Same text as user_prompt() in ml/llm_data.py. */
    fun userPrompt(source: String, sender: String, text: String): String {
        val body = URL.replace(text.replace(" || ", "\n"), " ").replace(SPACES, " ").trim().take(MAX_TEXT)
        return "Scam check. Source: ${source.ifEmpty { "unknown" }}. From: ${sender.ifEmpty { "unknown" }}.\n$body\nReply: LABEL | reason"
    }

    /** Returns null when the model is not loaded or its answer cannot be parsed. */
    override suspend fun judge(source: String, sender: String, text: String): Judgement? {
        val e = engine ?: return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val t0 = System.nanoTime()
                try {
                    // a fresh conversation per check: nothing carries over between messages
                    e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(1, 1.0, 0.0, 0))).use { conv ->
                        val reply = conv.sendMessage(userPrompt(source, sender, text))
                        val out = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
                        val m = ANSWER.find(out)
                        if (m == null) {
                            Log.d(TAG, "unparsed answer: $out")
                            null
                        } else {
                            Log.d(TAG, "${m.value} in $lastLatencyMs ms")
                            val level = when (m.groupValues[1]) { "SCAM" -> Level.DANGEROUS; "SUSPICIOUS" -> Level.SUSPICIOUS; else -> Level.SAFE }
                            Judgement(level, m.groupValues[2])
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "inference failed", t)
                    null
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
