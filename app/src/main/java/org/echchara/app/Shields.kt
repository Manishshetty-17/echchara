package org.echchara.app

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/*
 * Phone Shield. Everything here runs on the phone and nothing is uploaded.
 *  - Payment pause: during a call from an unknown number, and for 20 minutes after a scam call or message,
 *    opening a UPI or bank app shows a pause screen first. "Continue" only works after 5 seconds.
 *  - Screen-share guard: the same pause appears before AnyDesk, TeamViewer and similar apps open.
 *  - Message check: WhatsApp, Telegram, Instagram, Messenger, Signal and Gmail notifications go through the scam engine.
 *  - App check: apps installed from outside an app store that can read SMS, read notifications, use Accessibility
 *    or become a device admin are flagged, with an Uninstall button.
 *  - SIM alarm: if the SIM loses service while Wi-Fi still works, especially soon after a SIM or eSIM scam, you are warned.
 */

/** Official places to report and get help. */
object Links {
    const val CHAKSHU = "https://sancharsaathi.gov.in/sfc/"
    const val CYBERCRIME = "https://cybercrime.gov.in"
    const val ACTION_REPORT = "org.echchara.app.REPORT"

    fun view(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun home(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun uninstall(pkg: String): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun appInfo(pkg: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i); true } catch (e: Exception) { false }

    /** Copies the message so it can be pasted into the Chakshu form, then opens Chakshu. Call on the main thread. */
    fun report(ctx: Context, text: String) {
        if (text.isNotBlank()) {
            try { ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Echchara", text)) } catch (e: Exception) { }
            Toast.makeText(ctx, if (Store.kannada) "ಸಂದೇಶ ನಕಲಿಸಲಾಗಿದೆ. ಚಕ್ಷು ಫಾರ್ಮ್‌ನಲ್ಲಿ ಅಂಟಿಸಿ." else "Message copied. Paste it into the Chakshu form.", Toast.LENGTH_LONG).show()
        }
        start(ctx, view(CHAKSHU))
    }

    /** "Report on Chakshu" and "Call 1930" buttons for a scam notification. Uses request codes [code] and code + 1. */
    fun reportActions(ctx: Context, code: Int, text: String): List<Pair<String, PendingIntent>> {
        val kn = Store.kannada
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val report = Intent(ctx, MainActivity::class.java).setAction(ACTION_REPORT).putExtra("text", text.take(1500))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return listOfNotNull(
            PendingIntent.getActivity(ctx, code, report, flags)?.let { (if (kn) "ಚಕ್ಷುವಿನಲ್ಲಿ ವರದಿ" else "Report on Chakshu") to it },
            PendingIntent.getActivity(ctx, code + 1, dial("1930"), flags)?.let { (if (kn) "1930 ಗೆ ಕರೆ" else "Call 1930") to it }
        )
    }
}

/** Advice shared by the call, SMS and chat warnings. */
object Tips {
    private val CODE_WORD = Txt(
        "If they say they're family or your boss, hang up and call them back on the number you have saved, or ask for your family code word.",
        "ಕುಟುಂಬದವರು ಅಥವಾ ನಿಮ್ಮ ಬಾಸ್ ಎಂದು ಹೇಳಿದರೆ, ಕರೆ ಕಡಿತಗೊಳಿಸಿ ಉಳಿಸಿದ ಸಂಖ್ಯೆಗೆ ನೀವೇ ಕರೆ ಮಾಡಿ, ಅಥವಾ ಕುಟುಂಬದ ಗುಪ್ತ ಪದ ಕೇಳಿ."
    )

    /** The family code-word reminder, when the message or call claims to be a relative or a boss. */
    fun codeWord(a: Analysis): Txt? = if (a.flags.any { it.id == "family" || it.id == "boss" }) CODE_WORD else null

    private val OTP_WORD = Regex(
        """\botp\b|one[ -]?time ?(password|passcode|code|pin)|verification code|security code|login code|auth(entication)? code|passcode|\bcode\s*(is\b|:)|ಒಟಿಪಿ|ಒನ್ ಟೈಮ್ ಪಾಸ್‌ವರ್ಡ್""",
        RegexOption.IGNORE_CASE
    )
    // 4 to 8 digits on their own: not part of an amount like ₹4,999.00, but a full stop after the code is fine
    private val OTP_CODE = Regex("""(?<!\d|₹|\d[.,])\d{4,8}(?!\d|[.,]\d)""")

    fun looksLikeOtp(text: String): Boolean = OTP_WORD.containsMatchIn(text) && OTP_CODE.containsMatchIn(text)

    val OTP_TITLE = Txt("Don't read out this OTP", "ಈ OTP ಯಾರಿಗೂ ಹೇಳಬೇಡಿ")
    val OTP_ALERT = Txt(
        "An OTP just arrived while you are on a call with an unknown number. A real bank, police officer, delivery agent or customer care will never ask for it. If the caller asks, hang up.",
        "ಅಪರಿಚಿತ ಸಂಖ್ಯೆಯ ಕರೆಯಲ್ಲಿರುವಾಗ OTP ಬಂದಿದೆ. ನಿಜವಾದ ಬ್ಯಾಂಕ್, ಪೊಲೀಸ್, ಡೆಲಿವರಿ ಅಥವಾ ಗ್ರಾಹಕ ಸೇವೆ ಇದನ್ನು ಎಂದೂ ಕೇಳುವುದಿಲ್ಲ. ಕರೆ ಮಾಡಿದವರು ಕೇಳಿದರೆ ಕರೆ ಕಡಿತಗೊಳಿಸಿ."
    )
}

/** The 20 minutes after a likely scam call or message, when a payment is most likely to be a mistake. */
object RiskWindow {
    const val MINUTES = 20

    fun active(): Boolean = System.currentTimeMillis() < Store.riskUntil

    fun start(ctx: Context, reason: Txt) {
        Store.init(ctx)
        if (!Store.deviceShield) return
        val now = System.currentTimeMillis()
        Store.riskStart = now
        Store.riskUntil = now + MINUTES * 60_000L
        Store.riskReason = reason
        val c = ctx.applicationContext
        Handler(Looper.getMainLooper()).post { PayWatch.ensure(c) }
    }
}

/** Notices which app comes to the front, only while there is a reason to: an unknown call or the 20-minute risk window. */
@SuppressLint("StaticFieldLeak")
object PayWatch {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var app: Context? = null
    private var since = 0L
    private val allowed = HashMap<String, Long>()
    private val shown = HashMap<String, Long>()

    private val PAY_APPS = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.canarabank.mobility", "com.dreamplug.androidapp",
        "com.mobikwik_new", "com.freecharge.android", "com.amazon.mShop.android.shopping", "money.super.payments"
    )
    private val REMOTE_APPS = setOf(
        "com.anydesk.anydeskandroid", "com.teamviewer.quicksupport.market", "com.teamviewer.teamviewer.market.mobile",
        "com.teamviewer.host.market", "com.carriez.flutter_hbb", "com.rustdesk.rustdesk", "com.sand.airdroid",
        "com.sand.airdroidbiz", "com.rsupport.mvagent", "com.splashtop.remote.pad.v2"
    )
    private val UPI_SEGMENT = Regex("(^|[._])(upi|bhim)([._]|$)", RegexOption.IGNORE_CASE)

    fun isPayApp(pkg: String): Boolean = pkg in PAY_APPS || pkg.contains("bank", ignoreCase = true) || UPI_SEGMENT.containsMatchIn(pkg)
    fun isRemoteApp(pkg: String): Boolean = pkg in REMOTE_APPS

    fun hasUsageAccess(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    /** The user chose to continue; leave this app alone for five minutes. */
    fun allow(pkg: String) { allowed[pkg] = System.currentTimeMillis() }

    private fun watching(): Boolean = RiskWindow.active() || Guard.inUnknownCall

    /** Start watching if there is a reason to. Call on the main thread. */
    fun ensure(ctx: Context) {
        val c = ctx.applicationContext
        Store.init(c)
        if (!Store.deviceShield || !watching() || !hasUsageAccess(c) || !Settings.canDrawOverlays(c)) return
        startLoop(c)
        // During a call, Call Shield keeps Echchara running. Notification access keeps it running too.
        // Otherwise a small foreground service does, for the 20-minute window.
        if (RiskWindow.active() && !Guardian.connected && !PayWatchService.running) {
            try { ContextCompat.startForegroundService(c, Intent(c, PayWatchService::class.java)) } catch (e: Exception) { }
        }
    }

    fun startLoop(ctx: Context) {
        app = ctx.applicationContext
        if (running) return
        running = true
        since = System.currentTimeMillis() - 10 * 60_000L // the first look finds the app that is already open
        main.post(tick)
    }

    private fun stopLoop(c: Context) {
        running = false
        main.removeCallbacks(tick)
        try { c.stopService(Intent(c, PayWatchService::class.java)) } catch (e: Exception) { }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val c = app ?: run { running = false; return }
            if (!running) return
            if (!Store.deviceShield || !watching()) { stopLoop(c); return }
            val now = System.currentTimeMillis()
            val screenOn = c.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            if (screenOn) {
                val pkg = latestResumed(c, since, now)
                if (pkg != null && pkg != c.packageName) handle(c, pkg, now)
            }
            since = now - 500
            main.postDelayed(this, 1200)
        }
    }

    private fun latestResumed(c: Context, from: Long, to: Long): String? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = try { usm.queryEvents(from, to) } catch (e: Exception) { null } ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    private fun appLabel(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun handle(c: Context, pkg: String, now: Long) {
        if ((allowed[pkg] ?: 0L) > now - 5 * 60_000L) return
        if ((shown[pkg] ?: 0L) > now - 8_000L) return
        val remote = isRemoteApp(pkg)
        if (!remote && !isPayApp(pkg)) return
        shown[pkg] = now
        val kn = Store.kannada
        fun s(en: String, k: String) = if (kn) k else en
        val label = appLabel(c, pkg)
        val proceed = s("I'm sure, open $label", "ನನಗೆ ಖಚಿತ, $label ತೆರೆಯಿರಿ")
        val content = if (remote) PauseCard.Content(
            s("Pause before you share your screen", "ಪರದೆ ಹಂಚುವ ಮೊದಲು ನಿಲ್ಲಿ"),
            s("$label lets another person see and control this phone.", "$label ಮೂಲಕ ಬೇರೆಯವರು ಈ ಫೋನ್ ನೋಡಿ ನಿಯಂತ್ರಿಸಬಹುದು."),
            s("If a caller asked you to install or open it, hang up. Banks, police and customer care never need to see your screen.",
                "ಕರೆ ಮಾಡಿದವರು ಇದನ್ನು ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಲು ಅಥವಾ ತೆರೆಯಲು ಹೇಳಿದ್ದರೆ ಕರೆ ಕಡಿತಗೊಳಿಸಿ. ಬ್ಯಾಂಕ್, ಪೊಲೀಸ್ ಅಥವಾ ಗ್ರಾಹಕ ಸೇವೆಗೆ ನಿಮ್ಮ ಪರದೆ ನೋಡುವ ಅಗತ್ಯವೇ ಇಲ್ಲ."),
            proceed
        ) else {
            val title = if (Guard.inUnknownCall) {
                s("You're opening $label during a call from an unknown number.", "ಅಪರಿಚಿತ ಸಂಖ್ಯೆಯ ಕರೆಯಲ್ಲಿರುವಾಗ $label ತೆರೆಯುತ್ತಿದ್ದೀರಿ.")
            } else {
                val mins = ((now - Store.riskStart) / 60_000L).coerceAtLeast(0L)
                val why = Store.riskReason
                if (mins < 1) s("You're opening $label right after ${why.en}.", "${why.get(true)} ಬಂದ ತಕ್ಷಣ $label ತೆರೆಯುತ್ತಿದ್ದೀರಿ.")
                else s("You're opening $label $mins min after ${why.en}.", "${why.get(true)} ಬಂದ $mins ನಿಮಿಷದಲ್ಲಿ $label ತೆರೆಯುತ್ತಿದ್ದೀರಿ.")
            }
            PauseCard.Content(
                s("Pause before you pay", "ಪಾವತಿಸುವ ಮೊದಲು ನಿಲ್ಲಿ"), title,
                s("Scammers keep you on the line and rush you to pay or share an OTP. You never need your UPI PIN to receive money, and no officer collects money over a call.",
                    "ವಂಚಕರು ಕರೆಯಲ್ಲೇ ಇರಿಸಿಕೊಂಡು ಪಾವತಿಸಲು ಅಥವಾ OTP ಹಂಚಲು ಅವಸರಪಡಿಸುತ್ತಾರೆ. ಹಣ ಪಡೆಯಲು UPI PIN ಎಂದೂ ಬೇಕಿಲ್ಲ, ಯಾವ ಅಧಿಕಾರಿಯೂ ಕರೆಯಲ್ಲಿ ಹಣ ಕೇಳುವುದಿಲ್ಲ."),
                proceed
            )
        }
        show(c, content, pkg)
    }

    /** Opens the pause screen. If the phone blocks it (some phones stop apps opening screens from the background), draws it as an overlay instead. */
    private fun show(c: Context, content: PauseCard.Content, pkg: String) {
        val asked = System.currentTimeMillis()
        val i = Intent(c, PauseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(PauseActivity.PKG, pkg).putExtra(PauseActivity.HEAD, content.heading).putExtra(PauseActivity.TITLE, content.title)
            .putExtra(PauseActivity.BODY, content.body).putExtra(PauseActivity.GO, content.proceed)
        if (!Links.start(c, i)) { PauseOverlay.show(c, content, pkg); return }
        main.postDelayed({ if (PauseActivity.lastResume < asked) PauseOverlay.show(c, content, pkg) }, 1500)
    }
}

/** Keeps Echchara running during the 20-minute payment pause, with a visible notification. */
class PayWatchService : Service() {
    companion object { @Volatile var running = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this); Store.channels(this)
        val kn = Store.kannada
        val pi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Store.CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (kn) "ಪಾವತಿ ವಿರಾಮ ಆನ್ ಆಗಿದೆ" else "Payment pause is on")
            .setContentText(if (kn) "ವಂಚನೆಯ ಎಚ್ಚರಿಕೆಯ ನಂತರ 20 ನಿಮಿಷ, ಪಾವತಿ ಆ್ಯಪ್ ತೆರೆಯುವ ಮೊದಲು ಎಚ್ಚರ ನಿಲ್ಲಿಸಿ ಕೇಳುತ್ತದೆ" else "For 20 minutes after a scam warning, Echchara checks with you before a payment app opens")
            .setOngoing(true).setContentIntent(pi).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(43, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(43, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        running = true
        PayWatch.startLoop(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

/** The pause card, shared by the pause screen and the overlay fallback. */
object PauseCard {
    data class Content(val heading: String, val title: String, val body: String, val proceed: String)

    private const val WAIT_SECONDS = 5

    private fun dp(c: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun build(c: Context, content: Content, onLeave: () -> Unit, onCall: () -> Unit, onProceed: () -> Unit): View {
        val kn = Store.kannada
        val ink = Color.parseColor("#132A55")
        val pen = Color.parseColor("#D21F3C")

        fun tv(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(c).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(c, 8))
        }
        fun btn(label: String, fill: Int, textColor: Int, onClick: () -> Unit): Button = Button(c).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(c, 12).toFloat() }
            setOnClickListener { onClick() }
        }
        fun lp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 52)).apply { topMargin = dp(c, 8) }

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 18))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(c, 20).toFloat(); setStroke(dp(c, 3), pen) }
        }
        card.addView(tv("🛡 ಎಚ್ಚರ Echchara", 12f, ink, true))
        card.addView(tv(content.heading, 22f, pen, true))
        card.addView(tv(content.title, 16f, ink, true))
        card.addView(tv(content.body, 14f, Color.parseColor("#33476E"), false))
        card.addView(btn(if (kn) "ನಿಲ್ಲಿಸಿ, ಮುಖಪುಟಕ್ಕೆ ಹೋಗಿ" else "Stop and go to the home screen", ink, Color.WHITE, onLeave), lp())
        card.addView(btn(if (kn) "ಸಹಾಯಕ್ಕೆ 1930 ಗೆ ಕರೆ ಮಾಡಿ" else "Call 1930 for help", pen, Color.WHITE, onCall), lp())

        // "Continue" waits a few seconds. Scams work by rushing you; this breaks the rush.
        val go = btn(content.proceed, Color.parseColor("#EEF1F6"), ink, onProceed)
        go.isEnabled = false
        go.alpha = .6f
        var left = WAIT_SECONDS
        val countdown = object : Runnable {
            override fun run() {
                if (left <= 0) { go.isEnabled = true; go.alpha = 1f; go.text = content.proceed; return }
                go.text = content.proceed + " (" + left + ")"
                left--
                go.postDelayed(this, 1000)
            }
        }
        go.post(countdown)
        card.addView(go, lp())

        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60F1B31"))
            setPadding(dp(c, 18), dp(c, 24), dp(c, 18), dp(c, 24))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

/** Full-screen pause shown on top of a payment or screen-sharing app. */
class PauseActivity : ComponentActivity() {
    companion object {
        const val PKG = "pkg"
        const val HEAD = "head"
        const val TITLE = "title"
        const val BODY = "body"
        const val GO = "go"
        @Volatile var lastResume = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leave() }
        })
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    override fun onResume() {
        super.onResume()
        lastResume = System.currentTimeMillis()
        PauseOverlay.hide()
    }

    private fun render(i: Intent?) {
        val pkg = i?.getStringExtra(PKG)
        if (i == null || pkg == null) { finish(); return }
        val content = PauseCard.Content(i.getStringExtra(HEAD).orEmpty(), i.getStringExtra(TITLE).orEmpty(), i.getStringExtra(BODY).orEmpty(), i.getStringExtra(GO).orEmpty())
        setContentView(PauseCard.build(this, content,
            onLeave = { leave() },
            onCall = { Links.start(this, Links.dial("1930")); finish() },
            onProceed = { PayWatch.allow(pkg); finish() }
        ))
    }

    private fun leave() {
        Links.start(this, Links.home())
        finish()
    }
}

/** The pause card drawn over other apps, used when the pause screen can't be opened. */
@SuppressLint("StaticFieldLeak")
object PauseOverlay {
    private var view: View? = null
    private var ctx: Context? = null

    fun show(c0: Context, content: PauseCard.Content, pkg: String) {
        val c = c0.applicationContext
        if (!Settings.canDrawOverlays(c)) return
        hide()
        val wm = c.getSystemService(WindowManager::class.java) ?: return
        val v = PauseCard.build(c, content,
            onLeave = { hide(); Links.start(c, Links.home()) },
            onCall = { hide(); Links.start(c, Links.dial("1930")) },
            onProceed = { hide(); PayWatch.allow(pkg) }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(v, params); view = v; ctx = c } catch (e: Exception) { view = null }
    }

    fun hide() {
        val v = view ?: return
        val c = ctx ?: return
        try { c.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) { }
        view = null
    }
}

/** Flags apps installed from outside an app store that can read SMS or notifications, use Accessibility or become a device admin. */
object AppCheck {
    data class Risk(val pkg: String, val label: String, val reasons: List<Txt>, val key: String)

    private val STORES = setOf(
        "com.android.vending", "com.amazon.venezia", "com.sec.android.app.samsungapps", "com.huawei.appmarket",
        "com.xiaomi.mipicks", "com.xiaomi.market", "com.oppo.market", "com.heytap.market", "com.vivo.appstore", "com.bbk.appstore"
    )
    private const val FLAGS = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS

    fun inspect(ctx: Context, pkg: String): Risk? {
        if (pkg == ctx.packageName) return null
        val pm = ctx.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, FLAGS)
        } catch (e: Exception) { null } ?: return null
        val app = info.applicationInfo ?: return null
        if ((app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return null
        val installer: String? = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        } catch (e: Exception) { null }
        if (installer != null && installer in STORES) return null

        val perms: Set<String> = info.requestedPermissions?.filterNotNull()?.toSet() ?: emptySet()
        val services: Set<String> = info.services?.mapNotNull { it?.permission }?.toSet() ?: emptySet()
        val receivers: Set<String> = info.receivers?.mapNotNull { it?.permission }?.toSet() ?: emptySet()
        val reasons = mutableListOf<Txt>()
        if ("android.permission.RECEIVE_SMS" in perms || "android.permission.READ_SMS" in perms)
            reasons.add(Txt("Can read your SMS, including OTPs", "ನಿಮ್ಮ SMS ಮತ್ತು OTP ಓದಬಹುದು"))
        if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in services)
            reasons.add(Txt("Asks for Accessibility, which can see and tap your screen", "ಆಕ್ಸೆಸಿಬಿಲಿಟಿ ಕೇಳುತ್ತದೆ, ಪರದೆಯನ್ನು ನೋಡಿ ಒತ್ತಬಹುದು"))
        if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in services)
            reasons.add(Txt("Can read all your notifications", "ಎಲ್ಲ ಅಧಿಸೂಚನೆಗಳನ್ನು ಓದಬಹುದು"))
        if ("android.permission.BIND_DEVICE_ADMIN" in receivers)
            reasons.add(Txt("Asks to be a device admin, which makes it hard to remove", "ಸಾಧನ ನಿರ್ವಾಹಕ ಅನುಮತಿ ಕೇಳುತ್ತದೆ, ತೆಗೆದುಹಾಕುವುದು ಕಷ್ಟ"))
        if (reasons.isEmpty()) return null
        if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) reasons.add(Txt("Can draw over other apps", "ಇತರ ಆ್ಯಪ್‌ಗಳ ಮೇಲೆ ತೋರಿಸಬಹುದು"))
        if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms) reasons.add(Txt("Can install more apps", "ಇನ್ನಷ್ಟು ಆ್ಯಪ್‌ಗಳನ್ನು ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಬಹುದು"))
        val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { pkg }
        return Risk(pkg, label, reasons, pkg + "@" + info.firstInstallTime)
    }

    /** Every risky app on the phone, for "Check my apps now". Slow: run it off the main thread. */
    fun scanAll(ctx: Context): List<Risk> {
        val pm = ctx.packageManager
        val pkgs: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        } catch (e: Exception) { emptyList() }
        return pkgs.mapNotNull { p -> p.packageName?.let { inspect(ctx, it) } }.sortedBy { it.label.lowercase() }
    }

    fun inspectAndAlert(ctx: Context, pkg: String) {
        val r = inspect(ctx, pkg) ?: return
        val done = Store.alertedApps
        if (r.key in done) return
        Store.alertedApps = done + r.key
        alert(ctx, r)
    }

    /** Catches apps installed while Echchara was not running. The first run only records where things stand. */
    fun scanChanged(ctx: Context) {
        Store.init(ctx)
        if (!Store.deviceShield) return
        val boot = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) } catch (e: Exception) { -1 }
        val first = Store.pkgSeq < 0
        var seq = Store.pkgSeq
        if (boot != Store.pkgBoot) { Store.pkgBoot = boot; if (!first) seq = 0 } // sequence numbers restart at every boot
        val ch = try { ctx.packageManager.getChangedPackages(if (first) 0 else seq) } catch (e: Exception) { null }
        if (ch == null) { Store.pkgSeq = if (first) 0 else seq; return }
        if (!first) ch.packageNames.forEach { inspectAndAlert(ctx, it) }
        Store.pkgSeq = ch.sequenceNumber
    }

    private fun alert(ctx: Context, r: Risk) {
        val kn = Store.kannada
        val code = 1_000_000 + (r.pkg.hashCode() and 0xffff) * 2
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val title = if (kn) "ಅಪಾಯಕಾರಿ ಆ್ಯಪ್: ${r.label}" else "Risky app installed: ${r.label}"
        val body = (if (kn) "Play Store ಹೊರಗಿನಿಂದ ಇನ್‌ಸ್ಟಾಲ್ ಆಗಿದೆ. " else "Installed from outside the Play Store. ") +
            r.reasons.joinToString("; ") { it.get(kn) } +
            (if (kn) ". ನೀವು ಇದನ್ನು ಉದ್ದೇಶಿಸದಿದ್ದರೆ ಅಥವಾ ಯಾರೋ ಹೇಳಿದ್ದರಿಂದ ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿದ್ದರೆ, ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡಿ." else ". If you did not mean to install it, or someone asked you to, uninstall it.")
        Store.notifyAlert(ctx, 110_000 + (r.pkg.hashCode() and 0xffff), title, body, listOfNotNull(
            PendingIntent.getActivity(ctx, code, Links.uninstall(r.pkg), flags)?.let { (if (kn) "ಅನ್‌ಇನ್‌ಸ್ಟಾಲ್" else "Uninstall") to it },
            PendingIntent.getActivity(ctx, code + 1, Links.appInfo(r.pkg), flags)?.let { (if (kn) "ಆ್ಯಪ್ ಮಾಹಿತಿ" else "App info") to it }
        ))
        Store.addHistory(HistoryItem(System.currentTimeMillis(), "app", r.label, body, 80, "Risky app from outside the Play Store", "Play Store ಹೊರಗಿನ ಅಪಾಯಕಾರಿ ಆ್ಯಪ್"))
    }
}

/** Warns when the SIM loses service for a while but Wi-Fi still works: what a SIM swap looks like from the phone. */
@SuppressLint("StaticFieldLeak")
object SimWatch {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var cb: Any? = null
    private var lostAt = 0L
    private var alerted = false
    private val check = Runnable { evaluate() }

    private const val QUICK_MS = 90_000L          // soon after a SIM or eSIM scam message
    private const val NORMAL_MS = 15 * 60_000L    // any other time
    private const val RISK_HOURS = 6L
    private const val QUIET_HOURS = 72L           // at most one ordinary alarm every three days

    fun start(c0: Context) {
        if (cb != null) return
        val c = c0.applicationContext
        ctx = c
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val l = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        val st = serviceState.state
                        main.post { onState(st) }
                    }
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(c), l)
                cb = l
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onServiceStateChanged(serviceState: ServiceState?) {
                        val st = serviceState?.state ?: return
                        main.post { onState(st) }
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE)
                cb = l
            }
        } catch (e: Exception) { cb = null }
    }

    fun stop(c0: Context) {
        val l = cb ?: return
        val tm = c0.applicationContext.getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31 && l is TelephonyCallback) tm?.unregisterTelephonyCallback(l)
            else if (l is PhoneStateListener) @Suppress("DEPRECATION") tm?.listen(l, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) { }
        cb = null
        lostAt = 0L
        main.removeCallbacks(check)
    }

    private fun recentSimRisk(): Boolean = System.currentTimeMillis() - Store.simRiskAt < RISK_HOURS * 3600_000L

    private fun onState(state: Int) {
        when (state) {
            ServiceState.STATE_IN_SERVICE, ServiceState.STATE_POWER_OFF -> { lostAt = 0L; alerted = false; main.removeCallbacks(check) }
            ServiceState.STATE_OUT_OF_SERVICE, ServiceState.STATE_EMERGENCY_ONLY -> if (lostAt == 0L) {
                lostAt = System.currentTimeMillis()
                main.removeCallbacks(check)
                main.postDelayed(check, if (recentSimRisk()) QUICK_MS else NORMAL_MS)
            }
        }
    }

    private fun otherInternet(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val nc = cm.getNetworkCapabilities(n) ?: return false
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun evaluate() {
        val c = ctx ?: return
        Store.init(c)
        if (lostAt == 0L || alerted || !Store.deviceShield) return
        if (!otherInternet(c)) { main.postDelayed(check, 60_000L); return } // no Wi-Fi either: probably just no coverage
        val strong = recentSimRisk()
        val now = System.currentTimeMillis()
        if (!strong && now - Store.lastSimAlarm < QUIET_HOURS * 3600_000L) return
        alerted = true
        Store.lastSimAlarm = now
        val kn = Store.kannada
        val title = when {
            strong && kn -> "ನಿಮ್ಮ ಸಂಖ್ಯೆಯನ್ನು ಬೇರೆ SIM ಗೆ ಸರಿಸಿರಬಹುದು"
            strong -> "Your number may have been moved to another SIM"
            kn -> "15 ನಿಮಿಷದಿಂದ ಮೊಬೈಲ್ ಸಿಗ್ನಲ್ ಇಲ್ಲ"
            else -> "No mobile signal for 15 minutes"
        }
        val body = when {
            strong && kn -> "SIM ಅಥವಾ eSIM ವಂಚನೆಯ ಎಚ್ಚರಿಕೆಯ ನಂತರ ನಿಮ್ಮ SIM ಸೇವೆ ಕಳೆದುಕೊಂಡಿದೆ. ನೀವು ಲಿಂಕ್ ಒತ್ತಿದ್ದರೆ, QR ಕೋಡ್ ಸ್ಕ್ಯಾನ್ ಮಾಡಿದ್ದರೆ ಅಥವಾ OTP ಹಂಚಿದ್ದರೆ, ವಂಚಕರು ಈಗ ನಿಮ್ಮ OTP ಪಡೆಯುತ್ತಿರಬಹುದು. ಬೇರೆ ಫೋನ್‌ನಿಂದ ತಕ್ಷಣ ಬ್ಯಾಂಕ್‌ಗೆ ಕರೆ ಮಾಡಿ ಖಾತೆ ತಡೆಹಿಡಿಸಿ, ನಂತರ ಮೊಬೈಲ್ ಆಪರೇಟರ್ ಮತ್ತು 1930 ಗೆ ಕರೆ ಮಾಡಿ."
            strong -> "Your SIM lost service soon after Echchara warned you about a SIM or eSIM request. If you opened a link, scanned a QR code or shared an OTP, scammers may now be receiving your OTPs. From another phone, call your bank to freeze your account, then call your mobile operator and 1930."
            kn -> "Wi-Fi ಕೆಲಸ ಮಾಡುತ್ತಿದೆ, ಆದರೆ SIM ಗೆ 15 ನಿಮಿಷದಿಂದ ಸೇವೆ ಇಲ್ಲ. ಸಾಮಾನ್ಯವಾಗಿ ಇಲ್ಲಿ ಸಿಗ್ನಲ್ ಸಿಗುತ್ತಿದ್ದು, ನೀವು ಹೊಸ SIM ಅಥವಾ eSIM ಕೇಳದಿದ್ದರೆ, ಬೇರೆ ಫೋನ್‌ನಿಂದ ಬ್ಯಾಂಕ್ ಮತ್ತು ಆಪರೇಟರ್‌ಗೆ ಕರೆ ಮಾಡಿ."
            else -> "Wi-Fi works but your SIM has had no service for 15 minutes. If you usually get signal here and did not ask for a new SIM or eSIM, call your bank and your mobile operator from another phone."
        }
        Store.notifyAlert(c, 7100, title, body)
    }
}

/**
 * Notification access. Android keeps this service connected, so it is also the always-on home of the
 * new-app check, the SIM alarm and the payment pause. It only reads notifications from the chat and mail apps listed here.
 */
class Guardian : NotificationListenerService() {
    companion object {
        @Volatile var connected = false
        private val CHAT_APPS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X", "com.instagram.android" to "Instagram", "com.facebook.orca" to "Messenger",
            "org.thoughtcrime.securesms" to "Signal", "com.google.android.gm" to "Gmail"
        )
        private val recent = LinkedHashMap<String, Long>()
        private val worker = Executors.newSingleThreadExecutor()

        fun isEnabled(ctx: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    private var pkgReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        connected = true
        Store.init(this); Store.channels(this)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                val pkg = i.data?.schemeSpecificPart ?: return
                Store.init(c)
                if (!Store.deviceShield) return
                val app = c.applicationContext
                worker.execute { AppCheck.inspectAndAlert(app, pkg) }
            }
        }
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        try { ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED); pkgReceiver = r } catch (e: Exception) { }
        SimWatch.start(this)
        val app = applicationContext
        worker.execute { try { AppCheck.scanChanged(app) } catch (e: Exception) { } }
        PayWatch.ensure(this)
    }

    override fun onListenerDisconnected() {
        connected = false
        pkgReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        pkgReceiver = null
        SimWatch.stop(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val app = CHAT_APPS[n.packageName] ?: return
        Store.init(this)
        if (!Store.deviceShield) return
        val notif = n.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex: Bundle = notif.extras ?: return
        val who = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (text.length < 8) return
        val key = n.packageName + "|" + who + "|" + text
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val iter = recent.entries.iterator()
            while (iter.hasNext()) if (now - iter.next().value > 10 * 60_000L) iter.remove()
            if (recent.containsKey(key)) return
            recent[key] = now
        }
        val ctx = applicationContext
        worker.execute {
            val a = Engine.analyze(text)
            val apk = a.flags.any { it.id == "apk" }
            if (a.score >= 60 || apk) {
                val kn = Store.kannada
                val from = if (who.isBlank()) app else "$app · $who"
                Store.addHistory(HistoryItem(System.currentTimeMillis(), "chat", from, text, a.score, a.type?.en, a.type?.kn))
                val title = if (kn) "$from: ವಂಚನೆ ಸಂದೇಶದಂತೆ ಕಾಣುತ್ತದೆ" else "Likely scam on $from"
                val body = (a.type?.get(kn)?.let { "$it · " } ?: "") + a.summary.get(kn) + (Tips.codeWord(a)?.let { " " + it.get(kn) } ?: "")
                val h = key.hashCode() and 0xffff
                Store.notifyAlert(ctx, 200_000 + h, title, body, Links.reportActions(ctx, 600_000 + h * 2, "$from\n$text"))
                if (a.score >= 60) RiskWindow.start(ctx, Txt("a scam message on $app", "$app ನಲ್ಲಿ ವಂಚನೆ ಸಂದೇಶ"))
                if (a.flags.any { it.id == "esim" }) Store.simRiskAt = System.currentTimeMillis()
            }
        }
    }
}
