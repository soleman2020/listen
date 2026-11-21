package com.listen.reader

/** تنظيف النص وتحديد لغته وتقسيمه إلى مقاطع، كل مقطع يُنطق بصوت لغته. */
object LangUtils {

    enum class Lang { AR, EN }

    data class Segment(val text: String, val lang: Lang)

    /** مقطع لغة أقصر من هذا العدد من الكلمات يُضم إلى جاره حتى لا يتبدل الصوت عند كل كلمة. */
    private const val MIN_RUN_WORDS = 4

    /** محركات النطق ترفض النصوص الطويلة جدًا (الحد نحو 4000 حرف). */
    private const val MAX_CHUNK = 3000

    private val URL = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    // فيسبوك ينهي النص المقصوص بـ «… more» أو «… See more»
    private val SEE_MORE = Regex(
        """\s*((…|\.{2,})\s*(more|المزيد)|(…|\.{2,})?\s*(See more|Show more|Read more|عرض المزيد|إظهار المزيد|قراءة المزيد))\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex("""[ \t ]+""")
    private val LINE_BREAKS = Regex("""\s*\n\s*""")
    private val BREAK_NO_PUNCT = Regex("""([^.!?؟،:;…\n])\n""")

    private fun isArabic(c: Char) =
        c in 'ء'..'ي' || c in 'ٱ'..'ۓ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'ﻼ'

    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** يحذف الروابط وعبارة «عرض المزيد» ويوحّد المسافات مع الإبقاء على فواصل الأسطر. */
    fun clean(raw: String): String {
        var t = URL.replace(raw, " ")
        t = SEE_MORE.replace(t.trim(), "")
        t = SPACES.replace(t, " ")
        t = LINE_BREAKS.replace(t, "\n")
        return t.trim()
    }

    fun isMostlyEnglish(text: String): Boolean {
        var ar = 0
        var en = 0
        for (c in text) {
            if (isArabic(c)) ar++ else if (isLatin(c)) en++
        }
        return en >= 3 && en > ar * 2
    }

    private fun classify(token: String): Lang? {
        var ar = 0
        var en = 0
        for (c in token) {
            if (isArabic(c)) ar++ else if (isLatin(c)) en++
        }
        return when {
            ar == 0 && en == 0 -> null
            ar >= en -> Lang.AR
            else -> Lang.EN
        }
    }

    /** النص جاهزًا للنطق: بلا وسوم # و _ وبفواصل أسطر محوّلة إلى وقفات. */
    fun plain(text: String): String = forSpeech(text)

    private fun forSpeech(text: String): String {
        var t = text.replace('#', ' ').replace('_', ' ')
        // نهاية السطر بلا علامة ترقيم تصبح نقطة ليتوقف القارئ عندها
        t = BREAK_NO_PUNCT.replace(t, "$1. ")
        t = t.replace('\n', ' ')
        return SPACES.replace(t, " ").trim()
    }

    private class Run(val lang: Lang, val sb: StringBuilder, var words: Int)

    fun segments(raw: String): List<Segment> {
        val text = forSpeech(raw)
        if (text.isEmpty()) return emptyList()

        val runs = ArrayList<Run>()
        val lead = StringBuilder()
        for (tok in text.split(' ')) {
            if (tok.isEmpty()) continue
            val lang = classify(tok)
            val last = runs.lastOrNull()
            when {
                // أرقام ورموز: تتبع المقطع الحالي
                lang == null -> if (last == null) lead.append(tok).append(' ') else last.sb.append(' ').append(tok)
                last != null && last.lang == lang -> {
                    last.sb.append(' ').append(tok)
                    last.words++
                }
                else -> runs.add(Run(lang, StringBuilder(tok), 1))
            }
        }
        if (runs.isEmpty()) return chunk(text).map { Segment(it, Lang.AR) }
        if (lead.isNotEmpty()) runs[0].sb.insert(0, lead)

        while (runs.size > 1) {
            val i = runs.indices
                .filter { runs[it].words < MIN_RUN_WORDS }
                .minByOrNull { runs[it].words } ?: break
            val cur = runs[i]
            val prev = runs.getOrNull(i - 1)
            val next = runs.getOrNull(i + 1)
            if (next == null || (prev != null && prev.words >= next.words)) {
                prev!!.sb.append(' ').append(cur.sb)
                prev.words += cur.words
            } else {
                next.sb.insert(0, "${cur.sb} ")
                next.words += cur.words
            }
            runs.removeAt(i)
            var j = 1
            while (j < runs.size) {
                if (runs[j].lang == runs[j - 1].lang) {
                    runs[j - 1].sb.append(' ').append(runs[j].sb)
                    runs[j - 1].words += runs[j].words
                    runs.removeAt(j)
                } else {
                    j++
                }
            }
        }
        return runs.flatMap { r -> chunk(r.sb.toString()).map { Segment(it, r.lang) } }
    }

    private fun chunk(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > MAX_CHUNK) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '؟'), MAX_CHUNK)
            if (cut < MAX_CHUNK / 2) cut = rest.lastIndexOf(' ', MAX_CHUNK)
            if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }
}
