package com.phisherman.sms

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phisherman.voice.CallGuard
import com.phisherman.voice.CallWatcher

/**
 * Reads the OPEN chat in messaging apps. When a chat is on screen, apps like WhatsApp do not post
 * a notification for new messages, so the notification listener never sees them. This service
 * looks only at the listed chat apps, takes the incoming bubbles (left half of the screen), and
 * sends each new one through the same SMS engine. Nothing is stored.
 */
class ChatScreenReader : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val pending = Runnable { scan() }
    /** chat + text -> most copies of that text seen on screen at once; more copies = a new message */
    private val seen = object : LinkedHashMap<Int, Int>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?) = size > 1000
    }
    private var firstScanOf: String? = null

    private var banner: AlertBanner? = null

    override fun onServiceConnected() {
        Log.i(TAG, "connected")
        banner = AlertBanner(this)
        instance = this
    }

    override fun onDestroy() {
        banner?.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Called for any risky message (open chat, notification or SMS) while this service runs. */
    fun showBanner(body: String, v: com.phisherman.engine.Verdict) { banner?.show(body, v) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        if (pkg == CAPTION_PKG || (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && CallWatcher.inCall)) {
            if (CallWatcher.inCall) readCaptions()
            return
        }
        if (pkg !in CHAT_APPS) return
        main.removeCallbacks(pending)
        main.postDelayed(pending, 700)  // the chat settles after typing / scrolling
    }

    override fun onInterrupt() {}

    private fun scan() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val app = CHAT_APPS[pkg] ?: return
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val all = mutableListOf<Triple<Rect, String, String?>>()
        collect(root, all)
        // WhatsApp marks message bubbles with an id; elsewhere (chat list, other apps) use all text
        val bubbles = all.filter { it.third?.endsWith(":id/message_text") == true }
        if (pkg.startsWith("com.whatsapp") && bubbles.isEmpty()) return  // not inside a chat
        val texts = (if (bubbles.isNotEmpty()) bubbles else all).map { it.first to it.second }

        val sender = all.firstOrNull { it.third?.endsWith(":id/conversation_contact_name") == true }?.second
            ?: all.firstOrNull { it.first.top < height * 0.13 && it.second.length in 2..40 }?.second ?: "Open chat"
        val incoming = texts.filter { (r, t) ->
            r.top > height * 0.12 && r.bottom < height * 0.92 &&   // not the toolbar or the typing bar
                r.centerX() < width / 2 &&                           // left side = incoming bubble
                t.length >= 6 && !TIME_RE.matches(t)
        }.sortedBy { it.first.top }.map { it.second }

        // first look at a chat: only the newest few, so opening an old chat does not alert on history
        val chatKey = "$pkg|$sender"
        val first = firstScanOf != chatKey
        firstScanOf = chatKey
        // a scammer re-sending the same text is still a new message: compare how many copies are visible
        val counts = incoming.groupingBy { it }.eachCount()
        val newest = incoming.takeLast(3).toSet()
        for ((t, n) in counts) {
            val key = (chatKey + "\u0000" + t).hashCode()
            val before = seen[key] ?: 0
            if (n <= before) continue
            seen[key] = n
            if (first && t !in newest) continue  // opening a chat: do not alert on old history
            if (isDebuggable()) Log.d(TAG, "checking open-chat message in $app: \"${t.take(40)}\" (copy $n)")
            // the AI sees the last few incoming messages together, so it can follow the conversation
            val recent = incoming.takeLast(5).joinToString(" || ") { "Them: $it" }
            SmsHandler.handle(this, sender, t, null, app, recordSafe = false, forceNew = before > 0, context = recent)
        }
    }

    /**
     * During a call, Android gives other apps silence from the microphone, but the system Live
     * Caption feature can transcribe the call. Read its caption box and feed the call guard.
     */
    private fun readCaptions() {
        val box = windows.firstOrNull { it.root?.packageName?.toString() == CAPTION_PKG }?.root ?: return
        val parts = mutableListOf<Triple<Rect, String, String?>>()
        collect(box, parts)
        val text = parts.sortedBy { it.first.top }.joinToString(" ") { it.second }.trim()
        if (text.isEmpty()) return
        if (isDebuggable()) Log.d(TAG, "caption: \"${text.takeLast(50)}\"")
        CallGuard.onCaption(this, text)
    }

    private fun isDebuggable() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Triple<Rect, String, String?>>) {
        if (n == null) return
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && !n.isEditable && n.className?.toString()?.endsWith("EditText") != true) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out += Triple(r, text, n.viewIdResourceName)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    companion object {
        private const val TAG = "ChatScreenReader"
        @Volatile var instance: ChatScreenReader? = null
            private set

        /** Android System Intelligence hosts Live Caption */
        const val CAPTION_PKG = "com.google.android.as"
        private val TIME_RE = Regex("""(?i)\d{1,2}:\d{2}\s*(am|pm)?|today|yesterday|online|typing…?|last seen.*""")

        /** Chat apps whose open conversations are checked (same names as the notification listener). */
        val CHAT_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Samsung Messages",
            "com.facebook.orca" to "Messenger",
        )

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith("${ctx.packageName}/") && it.endsWith("ChatScreenReader") }
        }
    }
}
