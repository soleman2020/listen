package com.listen.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * صوت بشري معبّر عبر Gemini TTS (سحابي). يحتاج مفتاح API يضعه المستخدم في الإعدادات.
 * النص يُرسل إلى خوادم جوجل، والصوت يعود PCM/WAV ويُشغَّل عبر AudioTrack.
 */
class GeminiVoice(context: Context) {

    interface Listener {
        /** بدأ الصوت فعلًا. */
        fun onStart()
        fun onDone()

        /** فشل الطلب؛ [remaining] هو النص الذي لم يُقرأ بعد ليكمله صوت الجهاز. */
        fun onError(messageRes: Int, remaining: String)
    }

    private class ApiException(val code: Int, val messageRes: Int) : Exception("HTTP $code")

    private class Pcm(val data: ByteArray, val offset: Int, val length: Int, val sampleRate: Int, val channels: Int)

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    @Volatile private var generation = 0
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null

    fun speak(text: String, apiKey: String, voice: String, style: String, rate: Float, listener: Listener) {
        val gen = ++generation
        executor.execute { run(gen, text, apiKey, voice, style, rate, listener) }
    }

    fun stop() {
        generation++
        runCatching { connection?.disconnect() }
        track?.let { runCatching { it.pause(); it.flush() } }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        executor.shutdown()
    }

    /**
     * للتشخيص في نسخة التطوير: يجرّب طرق تمرير المفتاح المختلفة على طلب بسيط (قائمة النماذج)
     * ويسجّل رمز الرد فقط — لا يُسجَّل المفتاح نفسه.
     */
    fun diagnose(apiKey: String) {
        executor.execute {
            val base = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"
            val attempts = listOf(
                "x-goog-api-key" to { c: HttpURLConnection -> c.setRequestProperty("x-goog-api-key", apiKey) },
                "x-goog-api-key+revision" to { c: HttpURLConnection ->
                    c.setRequestProperty("x-goog-api-key", apiKey)
                    c.setRequestProperty("Api-Revision", API_REVISION)
                },
                "bearer" to { c: HttpURLConnection -> c.setRequestProperty("Authorization", "Bearer $apiKey") },
                "bearer+revision" to { c: HttpURLConnection ->
                    c.setRequestProperty("Authorization", "Bearer $apiKey")
                    c.setRequestProperty("Api-Revision", API_REVISION)
                },
            )
            for ((name, apply) in attempts) {
                try {
                    val conn = URL(base).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    apply(conn)
                    val code = conn.responseCode
                    val body = (if (code == 200) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val reason = Regex("\"(reason|status)\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                        .joinToString(",") { it.groupValues[2] }
                    Log.i(TAG, "diag [$name] HTTP $code $reason")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.i(TAG, "diag [$name] failed: $e")
                }
            }
            Log.i(TAG, "diag done")
        }
    }

    // ---------------------------------------------------------------- خيط العمل

    private fun run(gen: Int, text: String, apiKey: String, voice: String, style: String, rate: Float, listener: Listener) {
        val chunks = split(text)
        var started = false
        for ((i, chunk) in chunks.withIndex()) {
            if (gen != generation) return
            val pcm = try {
                fetch(chunk, apiKey, voice, style)
            } catch (e: Exception) {
                if (gen != generation) return // أُلغي الطلب من المستخدم
                Log.w(TAG, "request failed: $e")
                val res = (e as? ApiException)?.messageRes ?: R.string.cloud_network
                audioManager.abandonAudioFocusRequest(focusRequest)
                listener.onError(res, chunks.drop(i).joinToString(" "))
                return
            }
            if (gen != generation) return
            if (!started) {
                started = true
                audioManager.requestAudioFocus(focusRequest)
                listener.onStart()
            }
            play(pcm, rate, gen)
        }
        if (gen == generation) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            listener.onDone()
        }
    }

    private fun fetch(text: String, apiKey: String, voice: String, style: String): Pcm {
        var last: ApiException? = null
        for (model in MODELS) {
            try {
                return request(model, text, apiKey, voice, style)
            } catch (e: ApiException) {
                last = e
                // النموذج غير متاح للمفتاح أو انتهى حدّه: جرّب النموذج التالي
                if (e.code != 404 && e.code != 429) throw e
            }
        }
        throw last!!
    }

    private fun request(model: String, text: String, apiKey: String, voice: String, style: String): Pcm {
        val textPart = JSONObject().put("type", "text").put("text", text)
        if (style.isNotBlank()) {
            textPart.put(
                "annotations",
                JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style))
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(textPart)))
            )
            .put("response_format", JSONObject().put("type", "audio"))
            .put(
                "generation_config",
                JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice)))
            )

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setRequestProperty("Api-Revision", API_REVISION)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "$model HTTP $code: ${error.take(300)}")
                val res = when {
                    code == 429 -> R.string.cloud_quota
                    code == 401 || code == 403 || error.contains("API key", ignoreCase = true) -> R.string.cloud_bad_key
                    else -> R.string.cloud_failed
                }
                throw ApiException(code, res)
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val base64 = findAudio(if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json))
                ?: throw ApiException(200, R.string.cloud_failed)
            return decode(Base64.decode(base64, Base64.DEFAULT))
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    /** يبحث في الرد عن أول عنصر صوتي: {"type":"audio","data":"..."} (أو inlineData.data في الصيغة الأقدم). */
    private fun findAudio(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val data = node.optString("data")
                if (data.length > 64 && (node.optString("type") == "audio" || node.optString("mimeType").startsWith("audio"))) {
                    return data
                }
                val keys = node.keys()
                while (keys.hasNext()) findAudio(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findAudio(node.opt(i))?.let { return it }
        }
        return null
    }

    /** الرد WAV برأس RIFF، أو PCM خام 24 كيلوهرتز أحادي 16-بت. */
    private fun decode(bytes: ByteArray): Pcm {
        val isWav = bytes.size > 44 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        if (!isWav) return Pcm(bytes, 0, bytes.size, 24_000, 1)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate = 24_000
        var channels = 1
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val start = pos + 8
            if (id == "fmt " && start + 8 <= bytes.size) {
                channels = buf.getShort(start + 2).toInt().coerceIn(1, 2)
                sampleRate = buf.getInt(start + 4)
            } else if (id == "data") {
                val length = if (size < 0 || start + size > bytes.size) bytes.size - start else size
                return Pcm(bytes, start, length, sampleRate, channels)
            }
            if (size < 0) break
            pos = start + size + (size and 1)
        }
        return Pcm(bytes, 44, bytes.size - 44, sampleRate, channels)
    }

    private fun play(pcm: Pcm, rate: Float, gen: Int) {
        val frameBytes = 2 * pcm.channels
        val length = pcm.length - pcm.length % frameBytes
        if (length <= 0) return
        val channelMask = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(pcm.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.sampleRate * frameBytes / 2))
            .build()
        track = t
        try {
            if (rate != 1.0f) runCatching { t.playbackParams = t.playbackParams.setSpeed(rate) }
            t.play()
            var written = 0
            while (written < length && gen == generation) {
                val n = t.write(pcm.data, pcm.offset + written, minOf(8192, length - written))
                if (n <= 0) break
                written += n
            }
            // انتظر حتى يخرج آخر ما في المخزن من السماعة
            val totalFrames = written / frameBytes
            var lastHead = -1
            var stalledMs = 0
            while (gen == generation) {
                val head = t.playbackHeadPosition
                if (head >= totalFrames) break
                if (head == lastHead) {
                    stalledMs += 40
                    if (stalledMs > 1500) break
                } else {
                    stalledMs = 0
                    lastHead = head
                }
                Thread.sleep(40)
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: $e")
        } finally {
            track = null
            runCatching { t.pause(); t.flush() }
            t.release()
        }
    }

    /** طلب واحد لكل منشور ما أمكن (الحد المجاني يُحسب بعدد الطلبات)؛ النص الطويل جدًا يُقسَّم عند نهايات الجمل. */
    private fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > MAX_CHUNK) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '؟', '\n'), MAX_CHUNK)
            if (cut < MAX_CHUNK / 2) cut = rest.lastIndexOf(' ', MAX_CHUNK)
            if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    private companion object {
        const val TAG = "ListenGemini"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val API_REVISION = "2026-05-20"
        const val MAX_CHUNK = 3500
        val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")
    }
}
