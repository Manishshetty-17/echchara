package org.echchara.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val time: Long, val source: String, val sender: String, val text: String, val score: Int, val type: String?, val typeKn: String?)

/** Small persistent store: settings, alert history, feedback rows. */
object Store {
    private var prefs: SharedPreferences? = null
    val listeners = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun notifyListeners() { mainHandler.post { listeners.toList().forEach { it() } } }

    fun init(ctx: Context) { if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("echchara", Context.MODE_PRIVATE) }

    var kannada: Boolean
        get() = prefs?.getBoolean("kn", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("kn", v)?.apply() }

    var callShield: Boolean
        get() = prefs?.getBoolean("callShield", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("callShield", v)?.apply() }

    var smsShield: Boolean
        get() = prefs?.getBoolean("smsShield", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("smsShield", v)?.apply() }

    /** Voice help: read every result and scam-SMS warning aloud. */
    var voiceAuto: Boolean
        get() = prefs?.getBoolean("voiceAuto", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("voiceAuto", v)?.apply() }

    /** Phone Shield: payment pause, screen-share guard, message check, app check and SIM alarm. */
    var deviceShield: Boolean
        get() = prefs?.getBoolean("deviceShield", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("deviceShield", v)?.apply() }

    // ---------- Phone Shield state ----------
    private fun long(key: String) = prefs?.getLong(key, 0L) ?: 0L
    private fun putLong(key: String, v: Long) { prefs?.edit()?.putLong(key, v)?.apply() }
    private fun int(key: String, def: Int) = prefs?.getInt(key, def) ?: def
    private fun putInt(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }

    var riskStart: Long get() = long("riskStart"); set(v) = putLong("riskStart", v)
    var riskUntil: Long get() = long("riskUntil"); set(v) = putLong("riskUntil", v)
    var riskReason: Txt
        get() = Txt(prefs?.getString("riskWhy", null) ?: "a scam warning", prefs?.getString("riskWhyKn", null) ?: "ವಂಚನೆಯ ಎಚ್ಚರಿಕೆ")
        set(v) { prefs?.edit()?.putString("riskWhy", v.en)?.putString("riskWhyKn", v.kn)?.apply() }
    var simRiskAt: Long get() = long("simRiskAt"); set(v) = putLong("simRiskAt", v)
    var lastSimAlarm: Long get() = long("lastSimAlarm"); set(v) = putLong("lastSimAlarm", v)
    var pkgSeq: Int get() = int("pkgSeq", -1); set(v) = putInt("pkgSeq", v)
    var pkgBoot: Int get() = int("pkgBoot", -1); set(v) = putInt("pkgBoot", v)
    var alertedApps: Set<String>
        get() = prefs?.getStringSet("alertedApps", null)?.toSet() ?: emptySet()
        set(v) { prefs?.edit()?.putStringSet("alertedApps", HashSet(v.toList().takeLast(300)))?.apply() }

    fun history(): List<HistoryItem> {
        val arr = JSONArray(prefs?.getString("history", "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            HistoryItem(it.getLong("t"), it.getString("src"), it.optString("from"), it.optString("text"), it.getInt("s"), it.optString("type").ifEmpty { null }, it.optString("typeKn").ifEmpty { null })
        }
    }

    fun addHistory(item: HistoryItem) {
        val list = listOf(item) + history()
        val arr = JSONArray()
        list.take(60).forEach {
            arr.put(JSONObject().put("t", it.time).put("src", it.source).put("from", it.sender).put("text", it.text.take(2000)).put("s", it.score).put("type", it.type ?: "").put("typeKn", it.typeKn ?: ""))
        }
        prefs?.edit()?.putString("history", arr.toString())?.apply()
        notifyListeners()
    }

    fun clearHistory() { prefs?.edit()?.putString("history", "[]")?.apply(); notifyListeners() }

    fun feedbackRows(): List<Pair<Int, String>> {
        val p = prefs ?: return emptyList()
        val arr = JSONArray(p.getString("feedback", "[]") ?: "[]")
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); o.getInt("y") to o.getString("x") }
    }

    fun addFeedback(y: Int, x: String) {
        val arr = JSONArray(prefs?.getString("feedback", "[]") ?: "[]")
        arr.put(JSONObject().put("y", y).put("x", x.take(2000)))
        prefs?.edit()?.putString("feedback", arr.toString())?.apply()
        Engine.retrain()
    }

    // ---------- contacts ----------
    fun isContact(ctx: Context, number: String): Boolean {
        if (number.isBlank()) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) { false }
    }

    // ---------- notifications ----------
    const val CH_ALERT = "alerts"
    const val CH_GUARD = "guard"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Scam alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Warnings about scam messages and calls"; enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Call Shield", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while Echchara checks a call" })
    }

    fun notifyAlert(ctx: Context, id: Int, title: String, body: String, actions: List<Pair<String, PendingIntent>> = emptyList()) {
        // POST_NOTIFICATIONS only exists on Android 13+. Checking it on older phones always says "denied" and hid every alert.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels(ctx)
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFD21F3C.toInt())
            .setAutoCancel(true).setContentIntent(pi)
        actions.forEach { (label, action) -> b.addAction(0, label, action) }
        val n = b.build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (e: SecurityException) { }
    }
}
