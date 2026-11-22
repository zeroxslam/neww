package com.checklink.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives notifications ONLY after the user enables "Notification access" for CheckLink in
 * Android settings. Keeps the text of recent messaging notifications on the device (never uploaded).
 * It can only see what appears in the notification (usually the latest message), not whole chats.
 */
class MsgListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) { store(sbn) }

    // Also pick up notifications that are already showing when the listener connects
    override fun onListenerConnected() {
        try { activeNotifications?.forEach { store(it) } } catch (e: Exception) {}
    }

    private fun store(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // fullest text available: big text, then all lines, then plain text
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        val plain = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val text = listOf(big, lines, plain).filterNotNull().filter { it.isNotBlank() }.maxByOrNull { it.length } ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        // skip exact duplicates (same app + sender + text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("app") == app && o.optString("sender") == sender && o.optString("text") == text) return
        }
        val item = JSONObject().put("app", app).put("sender", sender).put("text", text).put("time", sbn.postTime)
        val out = JSONArray().put(item)
        for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    companion object {
        const val PREFS = "cl_msgs"
        const val KEY = "buf"
        const val MAX = 100
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "com.facebook.orca" to "Messenger",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Email",
            "com.microsoft.office.outlook" to "Email",
            "com.yahoo.mobile.client.android.mail" to "Email",
            "com.samsung.android.email.provider" to "Email",
            "com.google.android.apps.gmail" to "Email",
            "com.android.email" to "Email",
            "ch.protonmail.android" to "Email",
            "com.google.android.apps.messaging" to "SMS",
            "com.samsung.android.messaging" to "SMS"
        )
    }
}
