# CheckLink – Android app (source code, NOT built or tested)

This is a thin native shell around the CheckLink web app (`/app/` folder). It exists only to ask for
the REAL Android permissions that a web page cannot get:

* Notification access (reads the text of message notifications from WhatsApp, Messenger, Instagram,
  Telegram, email apps and the SMS app's notifications) – the user enables it in Android settings.
* Share ← CheckLink (text) – works in the Android share sheet.

Limits (important):
* It sees only what appears in notifications (usually the latest message), not whole chats.
* End-to-end encrypted chats are not "hacked": the app sees only what Android already shows in the notification.
* This build has NO SMS-reading permission (Google Play restricts it). Notification access is still reviewed by Google Play.

Build: open this folder in Android Studio (Giraffe or newer) → Sync → Build → Build APK(s).
If the web app URL changes, edit `HOME` in `MainActivity.kt`.

---
## تحديث 1.1: الصوت والملفات والأذونات

- المساعد دلوقتي بيستخدم **التعرّف على الصوت والنطق بتوع أندرويد نفسه** (WebView مابيدعمش الميكروفون بتاع المتصفح).
- زرار رفع الملفات/الصور اشتغل (`onShowFileChooser`). الاختيار من نافذة أندرويد، بدون إذن تخزين دائم.
- أول تشغيل بيطلب إذن **الميكروفون والكاميرا** بعد رسالة توضيح. السماعة مش محتاجة إذن.
- لازم ترفع ملفات الموقع (checklink-app.zip) على GitHub Pages **و** تبني الـ APK الجديد؛ الاتنين مرتبطين.

## لو Google Play Protect وقف التثبيت أو حظر التطبيق
ده بيحصل لأي APK بيتثبّت من برّه المتجر وتوقيعه جديد. الحلول بالترتيب:
1. وقّع الـ APK بمفتاح ثابت (workflow `create-signing-key` ثم `build-apk`) وبعدين ما تغيّرش المفتاح. التوقيع الثابت أحسن من debug.
2. عند الرسالة: اضغط **More details / تفاصيل أكثر** ثم **Install anyway / تثبيت على أي حال**. (من غير ما تقفل الحماية كلها.)
3. لو ظهرت "Send app for scanning": اضغطها، جوجل بتفحصه وبيخف التحذير.
4. الحل النهائي الوحيد: النشر على Google Play (Internal testing كفاية للتجربة).
5. تفعيل "الوصول للإشعارات" في أندرويد 13+ لتطبيق مثبّت من برّه: إعدادات ← التطبيقات ← CheckLink ← (⋮) ← **Allow restricted settings**، وبعدها فعّل الوصول للإشعارات.

## الأذونات في نسخة 1.1
| الإذن | ليه | بيتطلب إزاي |
|---|---|---|
| الميكروفون (RECORD_AUDIO) | المساعد الصوتي | نافذة أول تشغيل + عند أول استخدام |
| الكاميرا (CAMERA) | تصوير رسالة/صورة لتحليلها | نافذة أول تشغيل |
| الصور (READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE حتى أندرويد 12) | رفع صورة من المعرض | نافذة أول تشغيل |
| السماعة (MODIFY_AUDIO_SETTINGS) | تشغيل صوت المساعد | تلقائي، مش بيظهر للمستخدم |
| الوصول للإشعارات | كارت الرسائل | رسالة توضيح ثم إعدادات أندرويد (المستخدم يفعّله بإيده) |
| الإنترنت (INTERNET) | تحميل الموقع وVirusTotal | تلقائي |
