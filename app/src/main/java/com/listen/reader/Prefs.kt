package com.listen.reader

import android.content.Context

/** إعدادات التطبيق — يقرؤها كلٌّ من شاشة الإعدادات وخدمة القراءة. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("listen", Context.MODE_PRIVATE)

    var translate: Boolean
        get() = sp.getBoolean("translate", false)
        set(v) = sp.edit().putBoolean("translate", v).apply()

    var showCard: Boolean
        get() = sp.getBoolean("show_card", true)
        set(v) = sp.edit().putBoolean("show_card", v).apply()

    var allApps: Boolean
        get() = sp.getBoolean("all_apps", false)
        set(v) = sp.edit().putBoolean("all_apps", v).apply()

    var articleMode: Boolean
        get() = sp.getBoolean("article_mode", false)
        set(v) = sp.edit().putBoolean("article_mode", v).apply()

    /** فتح «عرض المزيد» تلقائيًا قبل القراءة. */
    var autoExpand: Boolean
        get() = sp.getBoolean("auto_expand", true)
        set(v) = sp.edit().putBoolean("auto_expand", v).apply()

    /** سرعة القراءة: 1.0 = السرعة العادية. */
    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** استخدام صوت Gemini البشري (سحابي) بدل صوت الجهاز. */
    var cloudVoice: Boolean
        get() = sp.getBoolean("cloud_voice", false)
        set(v) = sp.edit().putBoolean("cloud_voice", v).apply()

    /** مفتاح Gemini API الخاص بالمستخدم — يُحفظ على الجهاز فقط. */
    var geminiKey: String
        get() = sp.getString("gemini_key", "").orEmpty()
        set(v) = sp.edit().putString("gemini_key", v).apply()

    var geminiVoice: String
        get() = sp.getString("gemini_voice", "Sulafat").orEmpty()
        set(v) = sp.edit().putString("gemini_voice", v).apply()

    /** وصف نبرة الإلقاء المرسل إلى Gemini. */
    var voiceStyle: String
        get() = sp.getString("voice_style", DEFAULT_STYLE).orEmpty()
        set(v) = sp.edit().putString("voice_style", v).apply()

    /** أسلوب الردود المقترحة. */
    var replyStyle: String
        get() = sp.getString("reply_style", "ودود").orEmpty()
        set(v) = sp.edit().putString("reply_style", v).apply()

    /** الإملاء الصوتي بالإنجليزية بدل العربية. */
    var dictationEnglish: Boolean
        get() = sp.getBoolean("dictation_english", false)
        set(v) = sp.edit().putBoolean("dictation_english", v).apply()

    /** تصحيح وتنسيق النص المُملى عبر Gemini. */
    var dictationPolish: Boolean
        get() = sp.getBoolean("dictation_polish", true)
        set(v) = sp.edit().putBoolean("dictation_polish", v).apply()

    var readNotifications: Boolean
        get() = sp.getBoolean("read_notifications", false)
        set(v) = sp.edit().putBoolean("read_notifications", v).apply()

    /** مفاتيح التطبيقات التي تُقرأ إشعاراتها (انظر [NotifyService.APPS])، أو "all" لكل التطبيقات. */
    var notifyApps: Set<String>
        get() = sp.getStringSet("notify_apps", null) ?: setOf("whatsapp", "telegram", "messenger", "sms")
        set(v) = sp.edit().putStringSet("notify_apps", HashSet(v)).apply()

    /** قراءة الإشعارات بصوت Gemini (يستهلك من الحد المجاني). */
    var notifyCloud: Boolean
        get() = sp.getBoolean("notify_cloud", false)
        set(v) = sp.edit().putBoolean("notify_cloud", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    companion object {
        const val DEFAULT_STYLE =
            "warm, natural and expressive, like a person telling a friend what they just read; match the emotion of the text"
    }
}
