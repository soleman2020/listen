package com.listen.reader

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * عميل Gemini للنص والصور: تلخيص، اقتراح ردود، استخراج نص الصور، ترجمة الشاشة.
 * النتيجة تعود على الخيط الرئيسي.
 */
class GeminiText {

    /** ملف مرفق بالطلب: [type] هو "image" أو "audio". */
    class Media(val type: String, val mimeType: String, val bytes: ByteArray)

    private class ApiException(val code: Int, val messageRes: Int) : Exception("HTTP $code")

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0
    @Volatile private var connection: HttpURLConnection? = null

    /** نماذج لم ترد أو كانت مزدحمة ← الوقت الذي يُسمح بعده بتجربتها من جديد. */
    private val badUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [onResult]: النص المولَّد، أو null مع رسالة الخطأ. */
    fun generate(
        apiKey: String,
        system: String,
        prompt: String,
        media: Media?,
        onResult: (text: String?, errorRes: Int) -> Unit,
    ) {
        val gen = ++generation
        executor.execute {
            var text: String? = null
            var error = 0
            try {
                text = fetch(apiKey, system, prompt, media, gen)
            } catch (e: ApiException) {
                error = e.messageRes
            } catch (e: Exception) {
                Log.w(TAG, "request failed: $e")
                error = R.string.ai_network
            }
            main.post { if (gen == generation) onResult(text, error) }
        }
    }

    /**
     * للتشخيص في نسخة التطوير: يرسل طلبًا نصيًا صغيرًا بعدة صيغ ويسجّل رمز الرد وزمنه وأول الرد.
     */
    fun diagnose(apiKey: String) {
        executor.execute {
            // النماذج المتاحة لهذا المفتاح
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("x-goog-api-key", apiKey)
                val code = conn.responseCode
                val reply = (if (code == 200) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val names = Regex("\"name\"\\s*:\\s*\"models/([^\"]+)\"").findAll(reply).map { it.groupValues[1] }
                    .filter { it.startsWith("gemini") }.toList()
                Log.i(TAG, "diag models HTTP $code count=${names.size}")
                names.chunked(8).forEach { Log.i(TAG, "diag models: " + it.joinToString(", ")) }
                conn.disconnect()
            } catch (e: Exception) {
                Log.i(TAG, "diag models failed: $e")
            }

            fun text(s: String) = JSONArray().put(JSONObject().put("type", "text").put("text", s))
            val variants = MODELS.map { model ->
                model to JSONObject().put("model", model).put("input", text("Say hi in one word."))
                    .put("system_instruction", "Answer briefly.")
            }
            for ((name, body) in variants) {
                val start = System.currentTimeMillis()
                try {
                    val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 40_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("x-goog-api-key", apiKey)
                    conn.setRequestProperty("Api-Revision", API_REVISION)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val reply = (if (code == 200) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val ms = System.currentTimeMillis() - start
                    val summary = if (code == 200) {
                        "text=" + runCatching { outputText(JSONObject(reply)) }.getOrDefault("?").take(40) +
                            " | keys=" + runCatching { JSONObject(reply).keys().asSequence().joinToString(",") }.getOrDefault("?") +
                            " | status=" + runCatching { JSONObject(reply).optString("status") }.getOrDefault("?")
                    } else {
                        reply.replace(Regex("\\s+"), " ").take(220)
                    }
                    Log.i(TAG, "diag [$name] HTTP $code in ${ms}ms $summary")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.i(TAG, "diag [$name] failed after ${System.currentTimeMillis() - start}ms: $e")
                }
            }
            Log.i(TAG, "diag done")
        }
    }

    fun cancel() {
        generation++
        runCatching { connection?.disconnect() }
    }

    fun shutdown() {
        cancel()
        executor.shutdown()
    }

    /**
     * يجرّب النماذج بالترتيب: النموذج المزدحم أو غير المتاح يُتخطى فورًا ويُستبعد عشر دقائق،
     * حتى لا ينتظر المستخدم نموذجًا لا يرد.
     */
    private fun fetch(apiKey: String, system: String, prompt: String, media: Media?, gen: Int): String {
        val now = System.currentTimeMillis()
        val usable = MODELS.filter { (badUntil[it] ?: 0L) <= now }.ifEmpty { MODELS }
        var last: Exception? = null
        for ((index, model) in usable.withIndex()) {
            val isLast = index == usable.lastIndex
            try {
                return try {
                    request(model, apiKey, system, prompt, media, lowThinking = true, patient = isLast)
                } catch (e: ApiException) {
                    // بعض النماذج لا تقبل إعداد التفكير: أعد المحاولة بدونه
                    if (e.code != 400 || e.messageRes == R.string.ai_bad_key) throw e
                    request(model, apiKey, system, prompt, media, lowThinking = false, patient = isLast)
                }
            } catch (e: ApiException) {
                last = e
                val retryable = e.code == 404 || e.code == 429 || e.code >= 500
                if (!retryable) throw e
                badUntil[model] = System.currentTimeMillis() + BAD_MODEL_MS
            } catch (e: java.io.IOException) {
                if (gen != generation) throw e // أُلغي الطلب
                Log.w(TAG, "$model no answer: $e")
                last = e
                badUntil[model] = System.currentTimeMillis() + BAD_MODEL_MS
            }
        }
        throw last!!
    }

    private fun request(
        model: String,
        apiKey: String,
        system: String,
        prompt: String,
        media: Media?,
        lowThinking: Boolean,
        patient: Boolean,
    ): String {
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (media != null) {
            input.put(
                JSONObject()
                    .put("type", media.type)
                    .put("data", Base64.encodeToString(media.bytes, Base64.NO_WRAP))
                    .put("mime_type", media.mimeType)
            )
        }
        val body = JSONObject().put("model", model).put("system_instruction", system).put("input", input)
        if (lowThinking) body.put("generation_config", JSONObject().put("thinking_level", "low"))

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            // النموذج الأخير في القائمة ننتظره طويلًا؛ ما قبله ننتقل عنه سريعًا إن تأخر
            conn.readTimeout = if (patient) 90_000 else 25_000
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
                    code == 429 -> R.string.ai_quota
                    code == 401 || code == 403 || error.contains("API key", ignoreCase = true) -> R.string.ai_bad_key
                    else -> R.string.ai_failed
                }
                throw ApiException(code, res)
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val text = outputText(JSONObject(json))
            if (text.isBlank()) {
                Log.w(TAG, "$model empty output: ${json.take(300)}")
                throw ApiException(200, R.string.ai_failed)
            }
            return text
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    /** نص الرد: كل أجزاء النص في خطوات model_output. */
    private fun outputText(root: JSONObject): String {
        val sb = StringBuilder()
        val steps = root.optJSONArray("steps") ?: return ""
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
        }
        return sb.toString().trim()
    }

    private companion object {
        const val TAG = "ListenAi"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val API_REVISION = "2026-05-20"
        const val BAD_MODEL_MS = 10 * 60 * 1000L
        val MODELS = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-3.8-flash")
    }
}
