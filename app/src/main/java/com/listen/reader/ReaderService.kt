package com.listen.reader

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

/**
 * خدمة الوصول: تعرض زرًا عائمًا فوق فيسبوك وإكس.
 * ضغطة واحدة = قراءة المنشور الذي بمحاذاة الزر. ضغطة أخرى = إيقاف.
 * ضغطة مطوّلة = قائمة الأدوات (نص الصورة، ترجمة الشاشة، تلخيص، اقتراح رد، إملاء). السحب = تحريك الزر.
 */
class ReaderService : AccessibilityService() {

    private enum class State { IDLE, LOADING, SPEAKING, LISTENING }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var narrator: Narrator
    private val ai = GeminiText()
    private var translator: TranslatorHelper? = null
    private var recognizer: SpeechRecognizer? = null
    private val recorder = VoiceRecorder()

    /** القارئ يعمل الآن (يحضّر الصوت أو ينطق). */
    private var narrating = false

    private var bubble: ImageView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var menu: View? = null
    private var highlight: View? = null
    private var menuClosedAt = 0L

    private var state = State.IDLE
    private var inTargetApp = false
    private var requestId = 0
    private var alive = false
    private var debugReceiver: BroadcastReceiver? = null

    private val evaluate = Runnable { evaluateForeground() }
    private val removeHighlight = Runnable { removeView(highlight); highlight = null }

    // ---------------------------------------------------------------- دورة الحياة

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        narrator = Narrator(
            this,
            onStatus = { status ->
                if (alive && narrating) {
                    when (status) {
                        Narrator.Status.PREPARING -> setState(State.LOADING)
                        Narrator.Status.SPEAKING -> setState(State.SPEAKING)
                        Narrator.Status.IDLE -> {
                            narrating = false
                            setState(State.IDLE)
                        }
                    }
                }
            },
            onProblem = { if (alive) toast(it) },
        )
        alive = true
        evaluateForeground()
        registerDebugTap()
    }

    /**
     * في نسخة التطوير فقط: محاكاة أفعال الزر العائم من الكمبيوتر للاختبار:
     * adb shell am broadcast -a com.listen.reader.DEBUG_TAP -p com.listen.reader [--es action image|screen|summary|reply|dictate|menu]
     */
    private fun registerDebugTap() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!alive || bubble == null) return
                when (intent?.getStringExtra("action")) {
                    "image" -> readImage()
                    "screen" -> translateScreen()
                    "summary" -> summarize()
                    "reply" -> suggestReplies()
                    "dictate" -> startDictation()
                    "menu" -> showMenu()
                    else -> onBubbleTap()
                }
            }
        }
        val filter = IntentFilter("com.listen.reader.DEBUG_TAP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!alive) return
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, 150)
    }

    override fun onInterrupt() {
        if (alive) narrator.stop()
    }

    override fun onDestroy() {
        if (alive) {
            alive = false
            handler.removeCallbacksAndMessages(null)
            debugReceiver?.let { runCatching { unregisterReceiver(it) } }
            debugReceiver = null
            narrator.shutdown()
            ai.shutdown()
            translator?.close()
            runCatching { recognizer?.destroy() }
            recognizer = null
            recorder.cancel()
            removeView(bubble); bubble = null
            removeView(card); card = null
            removeView(menu); menu = null
            removeView(highlight); highlight = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- متى يظهر الزر

    private fun evaluateForeground() {
        val pkg = foregroundRoot(allowSelf = true)?.packageName?.toString() ?: return
        inTargetApp = pkg != packageName && pkg != SYSTEM_UI && (prefs.allApps || pkg in TARGET_PACKAGES)
        updateVisibility()
    }

    /** نافذة التطبيق الظاهر أمام المستخدم، مع تجاهل نوافذنا العائمة. */
    private fun foregroundRoot(allowSelf: Boolean): AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val isOverlay = runCatching {
                active.window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }.getOrDefault(false)
            val isSelf = active.packageName?.toString() == packageName
            if (!isOverlay && (allowSelf || !isSelf)) return active
        }
        return runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { allowSelf || it.packageName?.toString() != packageName }
        }.getOrNull()
    }

    private fun updateVisibility() {
        // أثناء العمل يبقى الزر ظاهرًا في أي تطبيق ليتمكن المستخدم من الإيقاف
        val show = inTargetApp || state != State.IDLE
        if (show) {
            if (bubble == null) addBubble()
        } else {
            removeView(bubble); bubble = null
            removeMenu()
            removeCard()
        }
    }

    private fun setState(newState: State) {
        state = newState
        refreshBubble()
        updateVisibility()
    }

    // ---------------------------------------------------------------- الزر العائم

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(54)
        val screen = screenSize()
        val view = ImageView(this).apply {
            val p = dp(13)
            setPadding(p, p, p, p)
            contentDescription = getString(R.string.bubble_desc)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (if (prefs.bubbleX >= 0) prefs.bubbleX else screen.x - size - dp(6)).coerceIn(0, screen.x - size)
            y = (if (prefs.bubbleY >= 0) prefs.bubbleY else screen.y * 45 / 100).coerceIn(0, screen.y - size)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (state == State.IDLE) showMenu() else onBubbleTap()
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    handler.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        removeMenu()
                    }
                    if (moved) {
                        val s = screenSize()
                        lp.x = (startX + dx.toInt()).coerceIn(0, s.x - size)
                        lp.y = (startY + dy.toInt()).coerceIn(0, s.y - size)
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        // التصق بأقرب حافة حتى لا يغطي الزر المحتوى
                        val s = screenSize()
                        lp.x = if (lp.x + size / 2 < s.x / 2) dp(6) else s.x - size - dp(6)
                        runCatching { wm.updateViewLayout(view, lp) }
                        prefs.bubbleX = lp.x
                        prefs.bubbleY = lp.y
                    } else if (!longFired) {
                        onBubbleTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }

        try {
            wm.addView(view, lp)
            bubble = view
            bubbleLp = lp
            refreshBubble()
        } catch (e: Exception) {
            bubble = null
        }
    }

    private fun refreshBubble() {
        val view = bubble ?: return
        val (icon, color) = when (state) {
            State.SPEAKING -> R.drawable.ic_stop to R.color.bubble_speaking
            State.LOADING -> R.drawable.ic_hourglass to R.color.bubble_loading
            State.LISTENING -> R.drawable.ic_mic to R.color.bubble_listening
            State.IDLE ->
                if (prefs.translate) R.drawable.ic_translate to R.color.bubble_translate
                else R.drawable.ic_speaker to R.color.bubble_idle
        }
        view.setImageResource(icon)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(color))
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun onBubbleTap() {
        // الضغطة التي أغلقت القائمة لا تبدأ قراءة
        if (SystemClock.uptimeMillis() - menuClosedAt < 350) return
        when (state) {
            State.SPEAKING -> narrator.stop()
            State.LOADING -> cancelPending()
            State.LISTENING -> if (recorder.isRecording) recorder.stop() else runCatching { recognizer?.stopListening() }
            State.IDLE -> readAtBubble()
        }
    }

    /** يلغي ما يجري الآن: ترجمة، توسيع منشور، طلب Gemini، أو تحضير الصوت. */
    private fun cancelPending() {
        requestId++
        ai.cancel()
        narrating = false
        narrator.stop()
        setState(State.IDLE)
    }

    // ---------------------------------------------------------------- قائمة الأدوات

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        removeMenu()
        val anchor = bubbleLp ?: return
        val items = listOf<Pair<String, () -> Unit>>(
            getString(R.string.menu_image) to { readImage() },
            getString(R.string.menu_screen) to { translateScreen() },
            getString(R.string.menu_summary) to { summarize() },
            getString(R.string.menu_reply) to { suggestReplies() },
            getString(R.string.menu_dictate) to { startDictation() },
            getString(if (prefs.translate) R.string.menu_translate_on else R.string.menu_translate_off) to { toggleTranslate() },
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF5202124.toInt())
            }
            val p = dp(6)
            setPadding(p, p, p, p)
        }
        for ((label, action) in items) {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener {
                    removeMenu()
                    action()
                }
            })
        }
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                removeMenu()
                true
            } else {
                false
            }
        }

        val screen = screenSize()
        box.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x * 3 / 4, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val size = dp(54)
        val onRight = anchor.x + size / 2 >= screen.x / 2
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (onRight) anchor.x - box.measuredWidth - dp(8) else anchor.x + size + dp(8)
            y = (anchor.y + size / 2 - box.measuredHeight / 2)
                .coerceIn(dp(32), maxOf(dp(32), screen.y - box.measuredHeight - dp(32)))
        }
        try {
            wm.addView(box, lp)
            menu = box
        } catch (e: Exception) {
            menu = null
        }
    }

    private fun removeMenu() {
        if (menu != null) {
            removeView(menu)
            menu = null
            menuClosedAt = SystemClock.uptimeMillis()
        }
    }

    private fun toggleTranslate() {
        prefs.translate = !prefs.translate
        toast(if (prefs.translate) R.string.translate_on else R.string.translate_off)
        refreshBubble()
    }

    // ---------------------------------------------------------------- قراءة المنشور

    /** يلتقط نص المنشور الذي بمحاذاة الزر (ويفتح «عرض المزيد» أولًا إن لزم) ثم يسلّمه إلى [onText]. */
    private fun pickText(allowExpand: Boolean = true, onText: (String) -> Unit) {
        val view = bubble ?: return
        val root = foregroundRoot(allowSelf = false)
        if (root == null) {
            toast(R.string.no_window)
            return
        }
        val picked = TextExtractor.extract(
            root, bubbleCenterY(view), screenSize().y, resources.displayMetrics.density, prefs.articleMode
        )
        val text = picked?.let { LangUtils.clean(it.text) }
        if (picked == null || text.isNullOrBlank()) {
            // التطبيق لا يكشف نص هذا المنشور: اقرأه من صورة الشاشة عبر Gemini إن وُجد مفتاح
            if (prefs.geminiKey.isBlank()) toast(R.string.no_text) else pickFromScreenshot(view, onText)
            return
        }
        flash(picked.rect)
        removeCard()

        val expand = picked.expand
        if (allowExpand && prefs.autoExpand && expand != null &&
            runCatching { expand.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        ) {
            val id = ++requestId
            setState(State.LOADING)
            handler.postDelayed({
                if (alive && id == requestId) {
                    setState(State.IDLE)
                    pickText(allowExpand = false, onText = onText)
                }
            }, 600)
            return
        }
        onText(text)
    }

    /** بديل الالتقاط حين لا يكشف التطبيق النص: تصوير الشاشة واستخراج نص المنشور الذي بمحاذاة الزر. */
    private fun pickFromScreenshot(view: View, onText: (String) -> Unit) {
        val percent = bubbleCenterY(view) * 100 / screenSize().y
        removeCard()
        captureScreen { shot ->
            if (shot == null) {
                toast(R.string.no_text)
                return@captureScreen
            }
            askAi(POST_OCR_SYSTEM, POST_OCR_PROMPT.format(percent), toJpeg(shot, null)) { text ->
                if (text.contains("NO_TEXT")) toast(R.string.no_text) else onText(LangUtils.clean(text))
            }
        }
    }

    private fun bubbleCenterY(view: View): Int {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return loc[1] + view.height / 2
    }

    private fun readAtBubble() = pickText { text -> readOrTranslate(text) }

    private fun readOrTranslate(text: String) {
        if (prefs.translate && LangUtils.isMostlyEnglish(text)) translateThenSpeak(text) else speakText(text)
    }

    private fun translateThenSpeak(text: String) {
        val id = ++requestId
        setState(State.LOADING)
        toast(R.string.translating)
        val helper = translator ?: TranslatorHelper().also { translator = it }
        helper.translate(text) { result ->
            if (!alive || id != requestId) return@translate
            if (result.isNullOrBlank()) {
                toast(R.string.translate_failed)
                speakText(text)
            } else {
                if (prefs.showCard) showCard(result)
                speakText(result)
            }
        }
    }

    private fun speakText(text: String) {
        if (LangUtils.plain(text).isEmpty()) {
            setState(State.IDLE)
            toast(R.string.no_text)
            return
        }
        narrating = true
        narrator.speak(text)
    }

    // ---------------------------------------------------------------- أدوات Gemini

    /** يرسل الطلب إلى Gemini ويسلّم النص الناتج إلى [onText]؛ عند الفشل يعرض السبب. */
    private fun askAi(system: String, prompt: String, media: GeminiText.Media?, onText: (String) -> Unit) {
        val key = prefs.geminiKey.trim()
        if (key.isEmpty()) {
            toast(R.string.ai_need_key)
            return
        }
        val id = ++requestId
        setState(State.LOADING)
        ai.generate(key, system, prompt, media) { text, errorRes ->
            if (!alive || id != requestId) return@generate
            setState(State.IDLE)
            if (text.isNullOrBlank()) toast(if (errorRes != 0) errorRes else R.string.ai_failed) else onText(text.trim())
        }
    }

    private fun summarize() = pickText { text ->
        askAi(SUMMARY_SYSTEM, text, null) { summary ->
            if (prefs.showCard) showCard(summary)
            speakText(summary)
        }
    }

    private fun suggestReplies() = pickText { text ->
        askAi(REPLY_SYSTEM.format(prefs.replyStyle), text, null) { result ->
            val replies = result.lines()
                .map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("""^\d+[.)\-]\s*"""), "").trim('"', '«', '»', ' ') }
                .filter { it.isNotEmpty() }
                .take(4)
            if (replies.isEmpty()) toast(R.string.ai_failed) else showCard(null, replies)
        }
    }

    /** يصوّر الشاشة ويستخرج النص المكتوب داخل صورة المنشور ثم يقرؤه. */
    private fun readImage() {
        val view = bubble ?: return
        val screen = screenSize()
        val y = bubbleCenterY(view)
        val imageRect = foregroundRoot(allowSelf = false)?.let { TextExtractor.findImageRect(it, y, screen.x, screen.y) }
        removeCard()
        captureScreen { shot ->
            if (shot == null) {
                toast(R.string.shot_failed)
                return@captureScreen
            }
            if (imageRect != null) flash(imageRect)
            val prompt = if (imageRect != null) OCR_PROMPT_CROPPED else OCR_PROMPT_SCREEN.format(y * 100 / screen.y)
            askAi(OCR_SYSTEM, prompt, toJpeg(shot, imageRect)) { text ->
                if (text.contains("NO_TEXT")) {
                    toast(R.string.no_image_text)
                } else {
                    if (prefs.showCard) showCard(text)
                    readOrTranslate(text)
                }
            }
        }
    }

    /** يصوّر الشاشة ويعرض ترجمة عربية لكل ما عليها. */
    private fun translateScreen() {
        removeCard()
        captureScreen { shot ->
            if (shot == null) {
                toast(R.string.shot_failed)
                return@captureScreen
            }
            askAi(SCREEN_SYSTEM, SCREEN_PROMPT, toJpeg(shot, null)) { text -> showCard(text) }
        }
    }

    private fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        // أخفِ نوافذنا حتى لا تظهر في اللقطة
        setOverlaysVisible(false)
        handler.postDelayed({
            if (!alive) return@postDelayed
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        setOverlaysVisible(true)
                        val bitmap = runCatching {
                            val buffer = result.hardwareBuffer
                            val copy = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            copy
                        }.getOrNull()
                        Log.d(TAG, "screenshot ${bitmap?.width}x${bitmap?.height}")
                        onResult(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed: $errorCode")
                        setOverlaysVisible(true)
                        onResult(null)
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "screenshot failed: $e")
                setOverlaysVisible(true)
                onResult(null)
            }
        }, 150)
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = v
        card?.visibility = v
        highlight?.visibility = v
    }

    private fun toJpeg(source: Bitmap, crop: Rect?): GeminiText.Media {
        var bitmap = source
        if (crop != null) {
            val r = Rect(crop)
            if (r.intersect(0, 0, source.width, source.height) && r.width() > 80 && r.height() > 80) {
                bitmap = Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height())
            }
        }
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return GeminiText.Media("image", "image/jpeg", out.toByteArray())
    }

    // ---------------------------------------------------------------- الإملاء الصوتي

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.mic_needed)
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        // مع مفتاح Gemini: نسجّل الصوت ويحوّله Gemini إلى نص (يفهم العامية ولا يحتاج حزم لغات على الجهاز)
        if (prefs.geminiKey.isNotBlank()) {
            startGeminiDictation()
            return
        }
        val engine = recognitionEngine()
        if (engine == null && !SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(R.string.stt_unavailable)
            return
        }
        runCatching { recognizer?.destroy() }
        val r = if (engine != null) {
            SpeechRecognizer.createSpeechRecognizer(this, engine)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishDictation(text)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "dictation error $error")
                if (state == State.LISTENING) setState(State.IDLE)
                toast(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.stt_nothing
                        // 12 و13: لغة الإملاء غير متاحة في محرك الجهاز
                        12, 13 -> R.string.stt_need_key
                        else -> R.string.stt_failed
                    }
                )
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (prefs.dictationEnglish) "en-US" else "ar-EG")
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try {
            r.startListening(intent)
            setState(State.LISTENING)
            toast(R.string.stt_listening)
        } catch (e: Exception) {
            Log.w(TAG, "dictation start failed: $e")
            toast(R.string.stt_failed)
        }
    }

    private fun startGeminiDictation() {
        val started = recorder.start { wav ->
            if (!alive) return@start
            if (state == State.LISTENING) setState(State.IDLE)
            if (wav == null) {
                toast(R.string.stt_nothing)
                return@start
            }
            val system = TRANSCRIBE_SYSTEM + if (prefs.dictationPolish) TRANSCRIBE_POLISH else TRANSCRIBE_VERBATIM
            askAi(system, TRANSCRIBE_PROMPT, GeminiText.Media("audio", "audio/wav", wav)) { text ->
                if (text.contains("NO_SPEECH")) toast(R.string.stt_nothing) else deliverDictation(text)
            }
        }
        if (started) {
            setState(State.LISTENING)
            toast(R.string.stt_listening)
        } else {
            toast(R.string.stt_failed)
        }
    }

    /** يفضّل محرك التعرف على الكلام من جوجل إن وُجد (محرك شاومي الصيني لا يدعم العربية). */
    private fun recognitionEngine(): ComponentName? {
        val services = runCatching {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        val google = services.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.serviceInfo.packageName.startsWith("com.google.") }
        return google?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }

    private fun finishDictation(text: String?) {
        if (state == State.LISTENING) setState(State.IDLE)
        if (text.isNullOrBlank()) {
            toast(R.string.stt_nothing)
            return
        }
        if (prefs.dictationPolish && prefs.geminiKey.isNotBlank()) {
            val id = ++requestId
            setState(State.LOADING)
            ai.generate(prefs.geminiKey.trim(), POLISH_SYSTEM, text, null) { polished, _ ->
                if (!alive || id != requestId) return@generate
                setState(State.IDLE)
                // إن فشل التنسيق نكتب النص كما سُمع
                deliverDictation(if (polished.isNullOrBlank()) text else polished.trim())
            }
        } else {
            deliverDictation(text)
        }
    }

    private fun deliverDictation(text: String) {
        if (insertIntoFocusedField(text)) {
            toast(R.string.stt_inserted)
        } else {
            copyToClipboard(text)
            showCard(text)
            toast(R.string.copied)
        }
    }

    /** يضيف النص إلى خانة الكتابة التي عليها المؤشر، إن وُجدت. */
    private fun insertIntoFocusedField(text: String): Boolean {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return false
        if (!node.isEditable || node.packageName?.toString() == packageName) return false
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) text else existing.trimEnd() + " " + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }
    }

    // ---------------------------------------------------------------- النوافذ المساعدة

    /** وميض حول المنطقة المختارة ليعرف المستخدم ما الذي يُقرأ. */
    private fun flash(rect: Rect) {
        handler.removeCallbacks(removeHighlight)
        removeView(highlight)
        val pad = dp(4)
        val view = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x221565C0)
                setStroke(dp(2), getColor(R.color.bubble_idle))
            }
        }
        val lp = WindowManager.LayoutParams(
            rect.width() + pad * 2, rect.height() + pad * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.left - pad
            y = rect.top - pad
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(view, lp)
            highlight = view
            handler.postDelayed(removeHighlight, 700)
        } catch (e: Exception) {
            highlight = null
        }
    }

    /**
     * بطاقة أسفل الشاشة: تعرض نصًا (ترجمة، ملخص، نص صورة) مع أزرار قراءة ونسخ،
     * أو قائمة ردود مقترحة يُنسخ الرد منها بضغطة.
     */
    private fun showCard(text: String?, replies: List<String>? = null) {
        removeCard()
        val pad = dp(14)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (text != null) {
            actions.addView(chip(getString(R.string.card_speak)) { speakText(text) })
            actions.addView(chip(getString(R.string.card_copy)) {
                copyToClipboard(text)
                toast(R.string.copied)
            })
        } else {
            actions.addView(TextView(this).apply {
                setText(R.string.card_reply_hint)
                setTextColor(0xFFBDC1C6.toInt())
                textSize = 13f
            })
        }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(chip(getString(R.string.close)) { removeCard() })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        if (text != null) {
            content.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 16f
                setLineSpacing(0f, 1.25f)
            })
        }
        replies?.forEach { reply ->
            content.addView(
                TextView(this).apply {
                    this.text = reply
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setLineSpacing(0f, 1.2f)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(0xFF3C4043.toInt())
                    }
                    setOnClickListener {
                        copyToClipboard(reply)
                        toast(if (insertIntoFocusedField(reply)) R.string.reply_inserted else R.string.reply_copied)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) },
            )
        }
        val scroll = MaxHeightScrollView(this, screenSize().y * 38 / 100).apply { addView(content) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF5202124.toInt())
            }
            setPadding(pad, dp(10), pad, pad)
            addView(actions)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) },
            )
        }
        val outer = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(box)
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(20)
        }
        try {
            wm.addView(outer, lp)
            card = outer
        } catch (e: Exception) {
            card = null
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFF3C4043.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun removeCard() {
        removeView(card)
        card = null
    }

    private fun removeView(view: View?) {
        if (view != null) runCatching { wm.removeView(view) }
    }

    // ---------------------------------------------------------------- أدوات

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return Point(b.width(), b.height())
        }
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return Point(m.widthPixels, m.heightPixels)
    }

    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val TAG = "ListenService"
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_IMAGE_SIDE = 2000

        val TARGET_PACKAGES = setOf(
            "com.facebook.katana", // فيسبوك
            "com.facebook.lite",   // فيسبوك لايت
            "com.twitter.android", // إكس
            "com.twitter.android.lite",
        )

        const val SUMMARY_SYSTEM =
            "لخّص النص الذي يرسله المستخدم بالعربية في جملتين إلى أربع جمل قصيرة واضحة تصلح للقراءة بصوت مسموع. " +
                "بدون عناوين أو نقاط أو مقدمات. أعد الملخص فقط."

        const val REPLY_SYSTEM =
            "اكتب ثلاثة ردود مقترحة قصيرة على المنشور أو التعليق الذي يرسله المستخدم، بنفس لغة ولهجة النص، بأسلوب: %s. " +
                "كل رد في سطر مستقل، بدون ترقيم أو علامات اقتباس أو أي شرح."

        const val POLISH_SYSTEM =
            "النص التالي أُملي صوتيًا. صحّح الإملاء وأضف علامات الترقيم دون تغيير الكلمات أو المعنى أو اللهجة. أعد النص فقط."

        const val TRANSCRIBE_SYSTEM =
            "Transcribe the speech in the audio in the language it is spoken. Arabic stays in Arabic script and keeps the " +
                "speaker's dialect; never translate. Output only the transcript, no commentary. " +
                "If there is no intelligible speech, return exactly: NO_SPEECH. "

        const val TRANSCRIBE_POLISH = "Add correct punctuation and fix obvious slips of the tongue without changing the meaning."

        const val TRANSCRIBE_VERBATIM = "Write the words exactly as spoken without rephrasing."

        const val TRANSCRIBE_PROMPT = "Transcribe this recording."

        const val OCR_SYSTEM =
            "You extract text from images taken from social media posts. Return ONLY the text written inside the image content, " +
                "exactly as written, in its original language and natural reading order. Ignore app interface elements such as " +
                "buttons, counters, usernames, timestamps and navigation bars. No commentary. " +
                "If the image contains no readable text, return exactly: NO_TEXT"

        const val POST_OCR_SYSTEM =
            "You read phone screenshots of social media apps. Return ONLY the complete text of the single post, comment or " +
                "message the user points at, exactly as written in its original language. Do not include usernames, " +
                "timestamps, counters, buttons or any other interface text, and add no commentary. " +
                "If there is no post text there, return exactly: NO_TEXT"

        const val POST_OCR_PROMPT =
            "The user points at about %d%% of the screen height from the top. Return the text of the post at or nearest to that position."

        const val OCR_PROMPT_CROPPED = "Extract the text written in this image."

        const val OCR_PROMPT_SCREEN =
            "This is a phone screenshot. Extract the text written inside the picture or post located around %d%% of the " +
                "screen height from the top."

        const val SCREEN_SYSTEM =
            "You translate everything readable in a phone screenshot into Arabic. Output only the Arabic translation as short " +
                "lines in top-to-bottom reading order, one line per interface item or paragraph. Skip the status bar (clock, " +
                "battery, signal). Text that is already Arabic is copied as is. No explanations."

        const val SCREEN_PROMPT = "Translate this screen into Arabic."
    }
}
