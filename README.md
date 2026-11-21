# اسمع — Listen

تطبيق أندرويد يقرأ أي منشور على فيسبوك أو إكس (تويتر) بصوت مسموع بضغطة واحدة، بالعربية والإنجليزية، ويترجم الإنجليزية إلى العربية.

**العربية** · [English](#listen-اسمع--english)

## المميزات

- **زر عائم** فوق فيسبوك وإكس (أو فوق كل التطبيقات): ضغطة تقرأ المنشور الذي بجانب الزر، وضغطة أخرى توقف القراءة.
- **اكتشاف اللغة تلقائيًا** وقراءة كل جزء بصوته الصحيح، عربي أو إنجليزي.
- **ترجمة الإنجليزية للعربية على الجهاز** بـ ML Kit، وتعمل بدون إنترنت بعد تحميل الحزمة (حوالي 30 م.ب).
- **فتح «عرض المزيد» تلقائيًا** لقراءة المنشور كاملًا، و**وضع المقال** لمتابعة باقي النص الظاهر.
- **قارئ الإشعارات**: يقرأ إشعارات التطبيقات التي تختارها، ويصمت والهاتف على الوضع الصامت.
- **أدوات ذكية** بضغطة مطوّلة على الزر (تحتاج مفتاح Gemini مجانيًا):
  - قراءة نص الصورة
  - ترجمة الشاشة
  - تلخيص المنشور
  - اقتراح رد
  - إملاء صوتي
- **صوت بشري معبّر** اختياري عبر Gemini TTS، مع الرجوع لصوت الجهاز عند انقطاع الإنترنت أو انتهاء الحد.

## التثبيت

1. حمّل ملف APK من صفحة [Releases](../../releases)، أو ابنِ التطبيق بنفسك (انظر الأسفل).
2. افتح التطبيق ← «فتح إعدادات إمكانية الوصول» ← فعّل **اسمع — قارئ المنشورات**.
   - على أندرويد 13 وما بعده قد تظهر رسالة «إعداد مقيّد» للتطبيقات المثبّتة من خارج المتجر. الحل: معلومات التطبيق ← القائمة ⋮ ← «السماح بالإعدادات المقيّدة».
3. للقراءة بالعربية بصوت الجهاز: ثبّت صوت العربية من إعدادات تحويل النص إلى كلام (Speech Services by Google).
4. اختياري: أنشئ مفتاح Gemini مجانيًا من [Google AI Studio](https://aistudio.google.com/apikey) والصقه في التطبيق.

## الخصوصية

- التطبيق لا يحفظ أي نص يقرؤه، وليس فيه تتبّع أو إعلانات.
- بدون مفتاح Gemini يعمل كل شيء على الجهاز: القراءة بصوت النظام، والترجمة بـ ML Kit.
- عند تفعيل ميزات Gemini فقط، يُرسل نص المنشور أو صورة الشاشة إلى خوادم جوجل لمعالجته.
- المفتاح يُحفظ على جهازك فقط.

## البناء من المصدر

المتطلبات: Android Studio حديث (JDK 17 أو أحدث) و Android SDK 36.

```bash
./gradlew assembleDebug
# الملف الناتج: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 (أندرويد 8)، targetSdk 35
- Kotlin، AccessibilityService، NotificationListenerService، ML Kit Translate، Android TTS، Gemini API

## ملاحظات

- قراءة المنشورات تعتمد على شجرة إمكانية الوصول في فيسبوك وإكس، فقد يحتاج التطبيق تعديلًا إذا غيّر أحدهما واجهته.
- على هواتف شاومي (HyperOS/MIUI): اسمح بالتشغيل التلقائي، واجعل البطارية «بلا قيود» حتى لا يوقف النظام الخدمة.

## المساهمة

البلاغات وطلبات الدمج مرحّب بها. افتح Issue لوصف المشكلة أو الفكرة.

## الرخصة

[MIT](LICENSE)

---

<div dir="ltr">

# Listen (اسمع) — English

An Android app that reads any Facebook or X (Twitter) post aloud with one tap, in Arabic and English, and translates English into Arabic.

## Features

- **Floating button** over Facebook and X (or over every app). Tap once to read the post next to the button; tap again to stop.
- **Automatic language detection**: each part is read with the right voice, Arabic or English.
- **On-device English → Arabic translation** with ML Kit. It works offline after a one-time download of about 30 MB.
- **Auto-expands "See more"** to read the whole post, and an **article mode** that keeps reading the rest of the visible text.
- **Notification reader**: reads notifications from the apps you choose, and stays quiet when the phone is on silent.
- **Smart tools** behind a long-press on the button (they need a free Gemini API key):
  - Read the text in an image
  - Translate the screen
  - Summarize a post
  - Suggest a reply
  - Voice dictation
- **Natural expressive voice** (optional) via Gemini TTS. It falls back to the device voice when you are offline or out of quota.

## Install

1. Download the APK from [Releases](../../releases), or build it yourself (see below).
2. Open the app → "Open accessibility settings" → enable **اسمع — قارئ المنشورات**.
   - On Android 13+ you may see "Restricted setting" for sideloaded apps. To fix it: App info → ⋮ menu → "Allow restricted settings".
3. To use the device voice in Arabic, install the Arabic voice in your text-to-speech settings (Speech Services by Google).
4. Optional: create a free Gemini key at [Google AI Studio](https://aistudio.google.com/apikey) and paste it into the app.

## Privacy

- The app never stores the text it reads. It has no tracking and no ads.
- Without a Gemini key, everything runs on the device: the system voice for reading and ML Kit for translation.
- The post text or a screenshot is sent to Google for processing only when you turn on Gemini features.
- Your key is stored only on your phone.

## Build from source

You need a recent Android Studio (JDK 17+) and Android SDK 36.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 (Android 8), targetSdk 35
- Kotlin, AccessibilityService, NotificationListenerService, ML Kit Translate, Android TTS, Gemini API

## Notes

- Reading relies on the accessibility tree of the Facebook and X apps, so it may need updates when they change their UI.
- On Xiaomi phones (HyperOS/MIUI), allow Autostart and set battery saver to "No restrictions" so the system doesn't kill the service.

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE)

</div>
