package com.listen.reader

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * يسجّل كلام المستخدم للإملاء: يتوقف تلقائيًا بعد صمت قصير يلي الكلام، أو عند [stop]، أو بعد دقيقة.
 * النتيجة ملف WAV في الذاكرة (16 كيلوهرتز أحادي)، أو null إن لم يُسمع كلام.
 */
class VoiceRecorder {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var cancelled = false

    val isRecording get() = running

    /** يتطلب إذن RECORD_AUDIO. [onDone] يُستدعى على الخيط الرئيسي. */
    @SuppressLint("MissingPermission")
    fun start(onDone: (wav: ByteArray?) -> Unit): Boolean {
        if (running) return false
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, RATE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed: $e")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        cancelled = false
        Thread({ capture(record, onDone) }, "listen-recorder").start()
        return true
    }

    /** ينهي التسجيل ويسلّم ما سُجّل. */
    fun stop() {
        running = false
    }

    /** ينهي التسجيل دون تسليم نتيجة. */
    fun cancel() {
        cancelled = true
        running = false
    }

    private fun capture(record: AudioRecord, onDone: (ByteArray?) -> Unit) {
        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(RATE / 10) // 100 مللي ثانية
        var heardSpeech = false
        var silenceMs = 0
        var elapsedMs = 0
        var noiseSum = 0.0
        var noiseCount = 0
        var threshold = MIN_THRESHOLD
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) {
                    bytes.putShort(buffer[i])
                    sum += buffer[i].toDouble() * buffer[i]
                }
                pcm.write(bytes.array(), 0, n * 2)
                val rms = sqrt(sum / n)
                val chunkMs = n * 1000 / RATE
                elapsedMs += chunkMs

                // أول 300 مللي ثانية تحدد مستوى ضجيج المكان
                if (elapsedMs <= 300) {
                    noiseSum += rms
                    noiseCount++
                    threshold = maxOf(MIN_THRESHOLD, noiseSum / noiseCount * 3)
                } else if (rms > threshold) {
                    heardSpeech = true
                    silenceMs = 0
                } else {
                    silenceMs += chunkMs
                }

                if (heardSpeech && silenceMs >= END_SILENCE_MS) break
                if (!heardSpeech && elapsedMs >= NO_SPEECH_MS) break
                if (elapsedMs >= MAX_MS) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "recording failed: $e")
        } finally {
            running = false
            runCatching { record.stop() }
            record.release()
        }
        Log.d(TAG, "recorded ${elapsedMs}ms speech=$heardSpeech bytes=${pcm.size()}")
        if (cancelled) return
        val result = if (heardSpeech && pcm.size() > 0) wav(pcm.toByteArray()) else null
        main.post { onDone(result) }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // قناة واحدة
        header.putInt(RATE)
        header.putInt(RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private companion object {
        const val TAG = "ListenRecorder"
        const val RATE = 16_000
        const val MIN_THRESHOLD = 500.0
        const val END_SILENCE_MS = 1_800
        const val NO_SPEECH_MS = 8_000
        const val MAX_MS = 60_000
    }
}
