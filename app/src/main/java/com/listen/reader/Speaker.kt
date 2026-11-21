package com.listen.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.listen.reader.LangUtils.Lang
import com.listen.reader.LangUtils.Segment
import java.util.Locale

/**
 * غلاف لمحرك تحويل النص إلى كلام: ينطق كل مقطع بصوت لغته (عربي / إنجليزي).
 * كل الاستدعاءات والردود على الخيط الرئيسي.
 */
class Speaker(
    context: Context,
    private val onStateChanged: (speaking: Boolean) -> Unit,
    private val onProblem: (messageRes: Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var ready = false
    private var failed = false
    private var pending: Pair<List<Segment>, Float>? = null
    private var batch = 0
    private var lastId = ""

    var isSpeaking = false
        private set

    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            pending = null
            onProblem(R.string.tts_failed)
            setSpeaking(false)
            return
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "started $utteranceId")
            }

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId, -1)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId, errorCode)
        })
        ready = true
        pending?.let { speak(it.first, it.second) }
        pending = null
    }

    private fun finished(utteranceId: String?, errorCode: Int = 0) {
        Log.d(TAG, "finished $utteranceId error=$errorCode")
        main.post { if (utteranceId == lastId) setSpeaking(false) }
    }

    fun speak(segments: List<Segment>, rate: Float) {
        if (segments.isEmpty()) return
        if (failed) {
            onProblem(R.string.tts_failed)
            setSpeaking(false)
            return
        }
        if (!ready) {
            pending = segments to rate
            return
        }
        tts.stop()
        tts.setSpeechRate(rate)
        batch++
        val warned = HashSet<Lang>()
        segments.forEachIndexed { i, seg ->
            // اللغة تُثبَّت مع كل مقطع لحظة إضافته إلى قائمة النطق
            val result = tts.setLanguage(if (seg.lang == Lang.AR) ARABIC else Locale.US)
            Log.d(TAG, "segment $i lang=${seg.lang} chars=${seg.text.length} setLanguage=$result")
            if ((result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) && warned.add(seg.lang)) {
                onProblem(if (seg.lang == Lang.AR) R.string.voice_missing_ar else R.string.voice_missing_en)
            }
            val id = "u$batch-$i"
            if (i == segments.lastIndex) lastId = id
            tts.speak(seg.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        audioManager.requestAudioFocus(focusRequest)
        setSpeaking(true)
    }

    fun stop() {
        pending = null
        lastId = ""
        if (ready) tts.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun setSpeaking(value: Boolean) {
        if (!value) audioManager.abandonAudioFocusRequest(focusRequest)
        isSpeaking = value
        onStateChanged(value)
    }

    private companion object {
        const val TAG = "ListenSpeaker"
        val ARABIC: Locale = Locale.forLanguageTag("ar")
    }
}
