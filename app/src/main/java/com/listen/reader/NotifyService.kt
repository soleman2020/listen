package com.listen.reader

import android.app.Notification
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** قارئ الإشعارات: ينطق إشعارات التطبيقات المختارة فور وصولها. */
class NotifyService : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var narrator: Narrator? = null
    private val queue = ArrayDeque<String>()
    private val lastSpoken = LinkedHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onDestroy() {
        narrator?.shutdown()
        narrator = null
        queue.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!prefs.readNotifications) return
        val pkg = sbn.packageName
        if (pkg == packageName || !isSelected(pkg)) return

        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // لا تقرأ والهاتف على الصامت أو الاهتزاز
        if (getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        // نفس الإشعار يُحدَّث أحيانًا دون تغيير محتواه
        val content = "$title|$text"
        if (lastSpoken[sbn.key] == content) return
        lastSpoken[sbn.key] = content
        if (lastSpoken.size > 60) lastSpoken.remove(lastSpoken.keys.first())

        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
        val spoken = listOf(app, title, text.take(MAX_TEXT)).filter { it.isNotEmpty() }.joinToString(". ")
        enqueue(LangUtils.clean(spoken))
    }

    private fun isSelected(pkg: String): Boolean {
        val keys = prefs.notifyApps
        if ("all" in keys) return true
        return keys.any { key -> APPS[key]?.contains(pkg) == true }
    }

    private fun enqueue(text: String) {
        if (text.isBlank()) return
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(text)
        val n = narrator ?: Narrator(
            this,
            onStatus = { status -> if (status == Narrator.Status.IDLE) speakNext() },
            onProblem = {},
        ).also { narrator = it }
        if (n.status == Narrator.Status.IDLE) speakNext()
    }

    private fun speakNext() {
        val next = queue.removeFirstOrNull() ?: return
        narrator?.speak(next, forceLocal = !prefs.notifyCloud)
    }

    companion object {
        private const val MAX_TEXT = 300
        private const val MAX_QUEUE = 5

        /** مفتاح الإعداد ← حزم التطبيق. */
        val APPS: Map<String, Set<String>> = mapOf(
            "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram" to setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
            "messenger" to setOf("com.facebook.orca", "com.facebook.mlite"),
            "sms" to setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
        )
    }
}
