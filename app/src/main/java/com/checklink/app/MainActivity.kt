package com.checklink.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AlertDialog
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCb: ValueCallback<Array<Uri>>? = null
    private var pendingWebReq: PermissionRequest? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ui = Handler(Looper.getMainLooper())
    private var pendingListenLang: String? = null

    // يفتح اختيار الملفات/الصور من أندرويد (بدون أي إذن تخزين) ويرجّع النتيجة للصفحة
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = filePathCb; filePathCb = null
        if (cb == null) return@registerForActivityResult
        cb.onReceiveValue(if (r.resultCode == Activity.RESULT_OK) FileChooserParams.parseResult(r.resultCode, r.data) else null)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // نتيجة طلب الأذونات: كمّل اللي كان مستني (طلب الصفحة أو الاستماع)
        val req = pendingWebReq; pendingWebReq = null
        if (req != null) {
            val ok = mutableListOf<String>()
            for (r in req.resources) {
                if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO)) ok.add(r)
                if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ok.add(r)
            }
            if (ok.isNotEmpty()) req.grant(ok.toTypedArray()) else req.deny()
        }
        askNotifOnce()
        val l = pendingListenLang; pendingListenLang = null
        if (l != null) { if (has(Manifest.permission.RECORD_AUDIO)) startListening(l) else js("window.__nSttErr&&window.__nSttErr('denied');window.__nSttEnd&&window.__nSttEnd()") }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun js(code: String) { runOnUiThread { try { web.evaluateJavascript(code, null) } catch (e: Exception) {} } }
    private fun q(t: String) = JSONObject.quote(t)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        // Android 15+ draws edge-to-edge: keep the page out from under the status/navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean {
                val u = r.url
                // keep CheckLink pages inside the app; everything else opens outside
                if (u.host == Uri.parse(HOME).host) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { false }
            }
        }
        // lets the web page ask for the microphone (voice assistant) through Android's normal permission
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = mutableListOf<String>()
                    for (r in request.resources) {
                        if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
                    }
                    if (need.isEmpty()) request.grant(request.resources)
                    else { pendingWebReq?.deny(); pendingWebReq = request; permLauncher.launch(need.toTypedArray()) }
                }
            }

            // دي كانت ناقصة: من غيرها زرار رفع الملف في WebView مابيعملش حاجة
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePathCb?.onReceiveValue(null)
                filePathCb = cb
                return try { fileLauncher.launch(params.createIntent()); true }
                catch (e: Exception) { filePathCb = null; cb.onReceiveValue(null); false }
            }
        }
        initTts()
        // The ONLY bridge: the page can ask about permissions and read messages the user allowed.
        web.addJavascriptInterface(Bridge(), "CheckLinkNative")
        web.loadUrl(buildUrl(intent))
        askFirstRunPermissions()
    }

    /** أول تشغيل: رسالة توضيح ثم طلب إذن الميكروفون والكاميرا (للمساعد الصوتي وقراءة الصور). السماعة (تشغيل الصوت) مش محتاجة إذن. */
    private fun askFirstRunPermissions() {
        val prefs = getSharedPreferences("cl_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("perm_asked", false)) return
        val need = mutableListOf<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        val photoPerm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!has(photoPerm)) need.add(photoPerm)
        if (need.isEmpty()) { prefs.edit().putBoolean("perm_asked", true).apply(); askNotifOnce(); return }
        val ar = Locale.getDefault().language == "ar"
        AlertDialog.Builder(this)
            .setTitle(if (ar) "أذونات التطبيق" else "App permissions")
            .setMessage(if (ar)
                "• الميكروفون: عشان تكلّم مساعد CheckLink بصوتك.\n• الكاميرا: عشان تصوّر رسالة أو صورة مش فاهمها ونحلّلها.\n• السماعة: بنشغّل رد المساعد بالصوت، وده مايحتاجش إذن.\n• الصور: عشان ترفع صورة وتحلّلها من المعرض.\n\nالصوت والصور بتتعالج على جهازك ومابتتخزّنش ولا بتترفع."
                else "• Microphone: to talk to the CheckLink assistant.\n• Camera: to photograph a message or image you want analysed.\n• Speaker: the assistant's spoken replies need no permission.\n• Photos: to upload an image from your gallery for analysis.\n\nAudio and images are processed on your device and are not stored or uploaded.")
            .setPositiveButton(if (ar) "موافق" else "Continue") { _, _ ->
                prefs.edit().putBoolean("perm_asked", true).apply()
                permLauncher.launch(need.toTypedArray())
            }
            .setNegativeButton(if (ar) "لاحقاً" else "Later", null)
            .show()
    }

    private fun askNotifOnce() {
        val prefs = getSharedPreferences("cl_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false) || notifEnabled()) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        ui.postDelayed({ Bridge().openNotifAccess() }, 600)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        web.loadUrl(buildUrl(intent))
    }

    /** Text shared from another app (Share ← CheckLink) is passed to the web page the same way as the PWA share target. */
    private fun buildUrl(i: Intent?): String {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val s = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            return HOME + "?title=" + Uri.encode(s) + "&text=" + Uri.encode(t)
        }
        return HOME
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    // ===== النطق (Text-to-Speech) بمحرك أندرويد =====
    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "last") js("window.__nTtsDone&&window.__nTtsDone()") }
                @Deprecated("old") override fun onError(id: String?) { if (id == "last") js("window.__nTtsDone&&window.__nTtsDone()") }
            })
        }
    }

    private fun localeOf(tag: String): Locale {
        val l = Locale.forLanguageTag(tag)
        val t = tts ?: return l
        return when (t.isLanguageAvailable(l)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Locale(l.language)
            else -> l
        }
    }

    private fun speakNow(text: String, tag: String) {
        val t = tts
        if (t == null || !ttsReady) { js("window.__nTtsDone&&window.__nTtsDone()"); return }
        t.setLanguage(localeOf(tag))
        t.stop()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val parts = text.split(Regex("""(?<=[.!?؟。\n])\s*""")).filter { it.isNotBlank() }.flatMap { it.chunked(max) }
        if (parts.isEmpty()) { js("window.__nTtsDone&&window.__nTtsDone()"); return }
        parts.forEachIndexed { i, p ->
            t.speak(p, TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i")
        }
    }

    // ===== التعرّف على الصوت (SpeechRecognizer) =====
    private fun startListening(tag: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { js("window.__nSttErr&&window.__nSttErr('svc');window.__nSttEnd&&window.__nSttEnd()"); return }
        try { recognizer?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                js("window.__nSttRes&&window.__nSttRes(" + q(t) + ",false)")
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) js("window.__nSttRes&&window.__nSttRes(" + q(t) + ",true)")
                js("window.__nSttEnd&&window.__nSttEnd()")
            }
            override fun onError(e: Int) {
                val c = when (e) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "denied"
                    SpeechRecognizer.ERROR_NO_MATCH -> "nomatch"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "nom"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "svc"
                    else -> "err$e"
                }
                js("window.__nSttErr&&window.__nSttErr(" + q(c) + ");window.__nSttEnd&&window.__nSttEnd()")
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    /** يفتح صفحة CheckLink مباشرة داخل «الوصول للإشعارات» (أندرويد 11+) أو القائمة العامة. التفعيل نفسه لازم المستخدم يعمله بإيده، أندرويد ماعندوش نافذة طلب لده. */
    private fun openListenerScreen() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, MsgListenerService::class.java).flattenToString()))
                return
            }
        } catch (e: Exception) {}
        try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) {}
    }

    private fun notifEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it == ComponentName(this, MsgListenerService::class.java).flattenToString() }
    }

    inner class Bridge {
        @JavascriptInterface fun ttsSpeak(text: String, lang: String) { runOnUiThread { speakNow(text, lang) } }
        @JavascriptInterface fun ttsStop() { runOnUiThread { try { tts?.stop() } catch (e: Exception) {} } }
        @JavascriptInterface fun sttStart(lang: String) {
            runOnUiThread {
                if (has(Manifest.permission.RECORD_AUDIO)) startListening(lang)
                else { pendingListenLang = lang; permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            }
        }
        @JavascriptInterface fun sttStop() { runOnUiThread { try { recognizer?.cancel() } catch (e: Exception) {} } }
        @JavascriptInterface fun openSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }

        @JavascriptInterface
        fun hasNotifAccess(): Boolean {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(this@MainActivity, MsgListenerService::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        /**
         * Google Play "prominent disclosure": explains what is read and why BEFORE sending the user to
         * Android's own Notification access screen, where the user turns it on.
         */
        @JavascriptInterface
        fun openNotifAccess() {
            val ar = Locale.getDefault().language == "ar"
            val title = if (ar) "السماح بقراءة الإشعارات" else "Allow notification access"
            val msg = if (ar)
                "يقرأ CheckLink نص إشعارات الرسائل (واتساب، ماسنجر، إنستجرام، تليجرام، الإيميل، تطبيق الرسائل) فقط ليساعدك على اكتشاف الروابط والرسائل الاحتيالية.\n\n• يُحفظ النص على جهازك فقط ولا يُرفع إلى أي خادم.\n• عند فحص رابط يُرسل الرابط وحده (دون نص الرسالة) إلى خدمة VirusTotal.\n• لا يُستخدم النص في الإعلانات ولا يُباع ولا يُشارك.\n• يمكنك إيقاف الإذن في أي وقت من إعدادات أندرويد.\n\nستنتقل الآن إلى إعدادات أندرويد لتفعيل الإذن بنفسك."
            else
                "CheckLink reads the text of message notifications (WhatsApp, Messenger, Instagram, Telegram, email, SMS app) only to help you spot scam links and messages.\n\n• The text is kept on your device only and is never uploaded.\n• When you scan a link, only the link (not the message text) is sent to VirusTotal.\n• It is not used for ads and is never sold or shared.\n• You can turn the permission off at any time in Android settings.\n\nYou will now be taken to Android settings to turn it on yourself."
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title).setMessage(msg)
                    .setPositiveButton(if (ar) "موافق، متابعة" else "Agree and continue") { _, _ -> openListenerScreen() }
                    .setNeutralButton(if (ar) "الزرار رمادي؟" else "Option greyed out?") { _, _ ->
                        // أندرويد 13+: التطبيق المثبّت من برّه محتاج "السماح بالإعدادات المقيّدة" الأول
                        android.widget.Toast.makeText(this@MainActivity, if (ar) "اضغط ⋮ فوق ثم «السماح بالإعدادات المقيّدة»، وارجع فعّل الوصول للإشعارات" else "Tap ⋮ then \"Allow restricted settings\", then come back and enable notification access", android.widget.Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton(if (ar) "إلغاء" else "Cancel", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun getMessages(): String {
            val out = JSONArray()
            if (hasNotifAccess()) {
                val prefs = getSharedPreferences(MsgListenerService.PREFS, Context.MODE_PRIVATE)
                try { val a = JSONArray(prefs.getString(MsgListenerService.KEY, "[]")); for (i in 0 until a.length()) out.put(a.get(i)) } catch (e: Exception) {}
            }
            return out.toString()
        }

        @JavascriptInterface
        fun openApp(key: String) {
            val pkg = when (key) {
                "whatsapp" -> "com.whatsapp"
                "messenger" -> "com.facebook.orca"
                "instagram" -> "com.instagram.android"
                "telegram" -> "org.telegram.messenger"
                "email" -> "com.google.android.gm"
                else -> null
            }
            runOnUiThread {
                try {
                    val i = if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
                    if (i != null) startActivity(i)
                } catch (e: Exception) {}
            }
        }
    }

    companion object { const val HOME = "https://zeroxslam.github.io/neww/app/" }
}
