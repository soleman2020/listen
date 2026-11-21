package com.listen.reader

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** ترجمة إنجليزي ← عربي على الجهاز نفسه (ML Kit). تُحمَّل حزمة اللغة مرة واحدة ثم تعمل بلا إنترنت. */
class TranslatorHelper {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    /** يستدعي [onResult] على الخيط الرئيسي بالنص المترجم، أو null عند الفشل. */
    fun translate(text: String, onResult: (String?) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .onSuccessTask {
                // كل فقرة تُترجم وحدها: النتيجة أدق والترتيب محفوظ
                val tasks = text.split('\n').filter { it.isNotBlank() }.map { translator.translate(it) }
                Tasks.whenAllSuccess<String>(tasks)
            }
            .addOnSuccessListener { parts -> onResult(parts.joinToString("\n")) }
            .addOnFailureListener { onResult(null) }
    }

    fun close() = translator.close()
}
