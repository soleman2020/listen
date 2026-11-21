package com.listen.reader

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

/** شاشة التفعيل والإعدادات. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var narrator: Narrator? = null

    private val modelManager = RemoteModelManager.getInstance()
    private val arabicModel = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
    private var downloading = false

    private lateinit var serviceStatus: TextView
    private lateinit var serviceHint: TextView
    private lateinit var modelStatus: TextView
    private lateinit var downloadModel: MaterialButton
    private lateinit var micAllow: MaterialButton
    private lateinit var notifySwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        serviceStatus = findViewById(R.id.serviceStatus)
        serviceHint = findViewById(R.id.serviceHint)
        modelStatus = findViewById(R.id.modelStatus)
        downloadModel = findViewById(R.id.downloadModel)

        findViewById<MaterialButton>(R.id.openA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialSwitch>(R.id.swTranslate).apply {
            isChecked = prefs.translate
            setOnCheckedChangeListener { _, checked ->
                prefs.translate = checked
                if (checked) downloadModelIfMissing()
            }
        }
        findViewById<MaterialSwitch>(R.id.swCard).apply {
            isChecked = prefs.showCard
            setOnCheckedChangeListener { _, checked -> prefs.showCard = checked }
        }
        findViewById<MaterialSwitch>(R.id.swExpand).apply {
            isChecked = prefs.autoExpand
            setOnCheckedChangeListener { _, checked -> prefs.autoExpand = checked }
        }
        findViewById<MaterialSwitch>(R.id.swArticle).apply {
            isChecked = prefs.articleMode
            setOnCheckedChangeListener { _, checked -> prefs.articleMode = checked }
        }
        findViewById<MaterialSwitch>(R.id.swAllApps).apply {
            isChecked = prefs.allApps
            setOnCheckedChangeListener { _, checked -> prefs.allApps = checked }
        }

        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        fun showRate(rate: Float) {
            rateLabel.text = getString(R.string.rate_label, String.format(Locale.US, "%.1f", rate))
        }
        findViewById<Slider>(R.id.rateSlider).apply {
            value = (Math.round(prefs.rate * 10) * 10f).coerceIn(50f, 200f)
            addOnChangeListener { _, v, _ ->
                prefs.rate = v / 100f
                showRate(v / 100f)
            }
        }
        showRate(prefs.rate)

        downloadModel.setOnClickListener { downloadModel() }
        setupCloudVoice()
        setupTools()

        findViewById<MaterialButton>(R.id.testVoice).setOnClickListener { testVoice() }
        findViewById<MaterialButton>(R.id.ttsSettings).setOnClickListener {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        }

        // للتشخيص من الكمبيوتر: am start -n com.listen.reader/.MainActivity --ez test_voice true
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_TEST_VOICE, false)) testVoice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // في نسخة التطوير فقط: --ez diag true يفحص طرق تمرير المفتاح المحفوظ ويسجّل رموز الرد
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra("diag", false)) {
            GeminiText().diagnose(prefs.geminiKey.trim())
            return
        }
        if (intent.getBooleanExtra(EXTRA_TEST_VOICE, false)) testVoice()
    }

    override fun onResume() {
        super.onResume()
        // الضغطة المطوّلة على الزر العائم قد تكون غيّرت إعداد الترجمة
        findViewById<MaterialSwitch>(R.id.swTranslate).isChecked = prefs.translate
        refreshServiceStatus()
        refreshModelStatus()
        refreshTools()
    }

    override fun onDestroy() {
        narrator?.shutdown()
        narrator = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- خدمة الوصول

    private fun refreshServiceStatus() {
        val on = isServiceEnabled()
        serviceStatus.setText(if (on) R.string.service_on else R.string.service_off)
        serviceHint.setText(if (on) R.string.service_on_hint else R.string.service_off_hint)
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ReaderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------------------------------------------------------------- حزمة الترجمة

    private fun refreshModelStatus() {
        if (downloading) return
        modelManager.isModelDownloaded(arabicModel)
            .addOnSuccessListener { showModelState(it) }
            .addOnFailureListener { showModelState(false) }
    }

    private fun showModelState(ready: Boolean) {
        modelStatus.setText(if (ready) R.string.model_ready else R.string.model_missing)
        downloadModel.visibility = if (ready) View.GONE else View.VISIBLE
        downloadModel.isEnabled = true
    }

    private fun downloadModelIfMissing() {
        modelManager.isModelDownloaded(arabicModel).addOnSuccessListener { ready -> if (!ready) downloadModel() }
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        modelStatus.setText(R.string.model_downloading)
        downloadModel.isEnabled = false
        modelManager.download(arabicModel, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                downloading = false
                showModelState(true)
            }
            .addOnFailureListener {
                downloading = false
                showModelState(false)
                modelStatus.setText(R.string.model_failed)
            }
    }

    // ---------------------------------------------------------------- تجربة الصوت

    private fun testVoice() {
        val n = narrator ?: Narrator(
            this,
            onStatus = {},
            onProblem = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
        ).also { narrator = it }
        // في نسخة التطوير فقط: --es test_key X يجرّب الصوت السحابي بمفتاح مؤقت لا يُحفظ
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val testKey = if (debuggable) intent.getStringExtra("test_key") else null
        if (n.status != Narrator.Status.IDLE) n.stop() else n.speak(getString(R.string.test_phrase), testKey)
    }

    // ---------------------------------------------------------------- الأدوات الذكية وقارئ الإشعارات

    private fun setupTools() {
        val styles = resources.getStringArray(R.array.reply_styles)
        findViewById<MaterialAutoCompleteTextView>(R.id.replyStyleInput).apply {
            setText(styles.firstOrNull { it == prefs.replyStyle } ?: styles[0], false)
            setOnItemClickListener { _, _, position, _ -> prefs.replyStyle = styles[position] }
        }
        findViewById<MaterialSwitch>(R.id.swDictationPolish).apply {
            isChecked = prefs.dictationPolish
            setOnCheckedChangeListener { _, checked -> prefs.dictationPolish = checked }
        }
        findViewById<MaterialSwitch>(R.id.swDictationEnglish).apply {
            isChecked = prefs.dictationEnglish
            setOnCheckedChangeListener { _, checked -> prefs.dictationEnglish = checked }
        }
        micAllow = findViewById(R.id.micAllow)
        micAllow.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }

        notifySwitch = findViewById(R.id.swNotify)
        notifySwitch.isChecked = prefs.readNotifications && hasNotificationAccess()
        notifySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.readNotifications = checked
            if (checked && !hasNotificationAccess()) {
                Toast.makeText(this, R.string.notify_access_needed, Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        findViewById<MaterialButton>(R.id.notifyApps).setOnClickListener { pickNotifyApps() }
        findViewById<MaterialSwitch>(R.id.swNotifyCloud).apply {
            isChecked = prefs.notifyCloud
            setOnCheckedChangeListener { _, checked -> prefs.notifyCloud = checked }
        }
    }

    private fun refreshTools() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micAllow.setText(if (granted) R.string.mic_granted else R.string.mic_allow)
        micAllow.isEnabled = !granted
        // المفتاح يعكس الحالة الفعلية: مفعّل في الإعداد والإذن ممنوح
        val on = prefs.readNotifications && hasNotificationAccess()
        if (notifySwitch.isChecked != on && !(prefs.readNotifications && !hasNotificationAccess())) {
            notifySwitch.isChecked = on
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) refreshTools()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NotifyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun pickNotifyApps() {
        val keys = arrayOf("whatsapp", "telegram", "messenger", "sms", "all")
        val labels = resources.getStringArray(R.array.notify_app_labels)
        val selected = prefs.notifyApps.toMutableSet()
        val checked = BooleanArray(keys.size) { keys[it] in selected }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notify_pick_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(keys[which]) else selected.remove(keys[which])
            }
            .setPositiveButton(R.string.ok) { _, _ -> prefs.notifyApps = selected }
            .show()
    }

    // ---------------------------------------------------------------- الصوت البشري (Gemini)

    private fun setupCloudVoice() {
        val cloudSwitch = findViewById<MaterialSwitch>(R.id.swCloud)
        cloudSwitch.isChecked = prefs.cloudVoice
        cloudSwitch.setOnCheckedChangeListener { _, checked -> prefs.cloudVoice = checked }

        findViewById<MaterialButton>(R.id.getKey).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
        }

        findViewById<TextInputEditText>(R.id.keyInput).apply {
            setText(prefs.geminiKey)
            doAfterTextChanged {
                val key = it?.toString().orEmpty().trim()
                val wasEmpty = prefs.geminiKey.isEmpty()
                prefs.geminiKey = key
                // أول لصق للمفتاح يفعّل الصوت البشري تلقائيًا
                if (wasEmpty && key.isNotEmpty()) cloudSwitch.isChecked = true
            }
        }

        val voices = resources.getStringArray(R.array.gemini_voices)
        findViewById<MaterialAutoCompleteTextView>(R.id.voiceInput).apply {
            setText(voices.firstOrNull { it.startsWith(prefs.geminiVoice) } ?: voices[0], false)
            setOnItemClickListener { _, _, position, _ -> prefs.geminiVoice = voices[position].substringBefore(' ') }
        }

        findViewById<TextInputEditText>(R.id.styleInput).apply {
            setText(prefs.voiceStyle)
            doAfterTextChanged { prefs.voiceStyle = it?.toString().orEmpty() }
        }
    }

    private companion object {
        const val REQUEST_MIC = 1
        const val EXTRA_TEST_VOICE = "test_voice"
    }
}
