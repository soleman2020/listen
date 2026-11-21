package com.listen.reader

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * القارئ: يستخدم صوت Gemini البشري إن كان مفعّلًا وله مفتاح، وإلا — أو عند أي فشل — صوت الجهاز.
 * كل الاستدعاءات والردود على الخيط الرئيسي.
 */
class Narrator(
    context: Context,
    private val onStatus: (Status) -> Unit,
    private val onProblem: (messageRes: Int) -> Unit,
) {
    enum class Status { IDLE, PREPARING, SPEAKING }

    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val cloud = GeminiVoice(context)
    private var usingLocal = false
    private var session = 0

    private val local = Speaker(
        context,
        onStateChanged = { speaking -> if (usingLocal) setStatus(if (speaking) Status.SPEAKING else Status.IDLE) },
        onProblem = onProblem,
    )

    var status = Status.IDLE
        private set

    /**
     * [keyOverride]: للاختبار فقط — يجبر استخدام الصوت السحابي بهذا المفتاح دون حفظه.
     * [forceLocal]: اقرأ بصوت الجهاز حتى لو كان الصوت السحابي مفعّلًا (لتوفير الحد المجاني).
     */
    fun speak(text: String, keyOverride: String? = null, forceLocal: Boolean = false) {
        halt()
        val id = ++session
        val key = keyOverride ?: prefs.geminiKey.trim()
        val plain = LangUtils.plain(text)
        if (plain.isEmpty()) {
            setStatus(Status.IDLE)
            return
        }
        if (forceLocal || key.isEmpty() || (keyOverride == null && !prefs.cloudVoice)) {
            speakLocal(text)
            return
        }
        setStatus(Status.PREPARING)
        cloud.speak(plain, key, prefs.geminiVoice, prefs.voiceStyle.trim(), prefs.rate, object : GeminiVoice.Listener {
            override fun onStart() {
                main.post { if (id == session) setStatus(Status.SPEAKING) }
            }

            override fun onDone() {
                main.post { if (id == session) setStatus(Status.IDLE) }
            }

            override fun onError(messageRes: Int, remaining: String) {
                main.post {
                    if (id == session) {
                        onProblem(messageRes)
                        speakLocal(remaining)
                    }
                }
            }
        })
    }

    private fun speakLocal(text: String) {
        val segments = LangUtils.segments(text)
        if (segments.isEmpty()) {
            setStatus(Status.IDLE)
            return
        }
        usingLocal = true
        setStatus(Status.SPEAKING)
        local.speak(segments, prefs.rate)
    }

    fun stop() {
        halt()
        setStatus(Status.IDLE)
    }

    fun shutdown() {
        halt()
        cloud.shutdown()
        local.shutdown()
    }

    /** يوقف أي قراءة جارية دون إبلاغ المستمع. */
    private fun halt() {
        session++
        usingLocal = false
        cloud.stop()
        local.stop()
    }

    private fun setStatus(value: Status) {
        status = value
        onStatus(value)
    }
}
