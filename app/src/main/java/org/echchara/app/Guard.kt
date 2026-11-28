package org.echchara.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Call Shield. For a call from a number that is not in the user's contacts it:
 *  1. shows a pop-up over the call screen as soon as the phone rings,
 *  2. turns the caller's speech into text once the call is answered (best on speaker),
 *  3. checks the growing transcript with the scam engine after every sentence,
 *  4. turns the pop-up into a red "hang up" alert when the call looks like fraud.
 * Nothing is recorded or stored except the final verdict and transcript in the local history.
 */
@SuppressLint("StaticFieldLeak")
object Guard {
    enum class Mode { CALL, SAMPLE, MIC }

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    @Volatile private var mode = Mode.CALL
    @Volatile private var active = false
    private var answered = false
    private var number = ""
    private val lines = mutableListOf<String>()
    private var partial = ""
    private var alerted = false
    private var silentErrors = 0
    private var errorStreak = 0
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var telephonyCallback: Any? = null

    // overlay views
    private var root: LinearLayout? = null
    private var tTitle: TextView? = null
    private var tStatus: TextView? = null
    private var tRisk: TextView? = null
    private var bar: ProgressBar? = null
    private var tTranscript: TextView? = null
    private var alertBox: LinearLayout? = null
    private var tReasons: TextView? = null
    private var tVerdict: TextView? = null
    private var listenBtn: Button? = null
    private var recBtn: Button? = null
    private var lp: WindowManager.LayoutParams? = null

    val isActive get() = active
    /** True while Call Shield is checking a real call from a number that is not in the contacts. */
    val inUnknownCall get() = active && mode == Mode.CALL
    private val kn get() = Store.kannada
    private fun s(en: String, knText: String) = if (kn) knText else en

    // ---------------- public entry points ----------------
    fun startForCall(c: Context, num: String) = main.post { begin(c, Mode.CALL, num); watchCallState(); }

    fun startSample(c: Context, script: List<String>, num: String) = main.post {
        begin(c, Mode.SAMPLE, num); answered = true; status(s("Checking this call (sample)", "ಈ ಕರೆಯನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದೆ (ಮಾದರಿ)"))
        script.forEachIndexed { i, l -> main.postDelayed({ if (active && mode == Mode.SAMPLE) addLine(l) }, 1500L + i * 2600L) }
        main.postDelayed({ if (active && mode == Mode.SAMPLE && !alerted) finish(true) }, 1500L + script.size * 2600L + 2500L)
    }

    fun startMic(c: Context) = main.post {
        begin(c, Mode.MIC, s("Microphone test", "ಮೈಕ್ರೊಫೋನ್ ಪರೀಕ್ಷೆ")); answered = true; startListening()
    }

    fun stop() = main.post { finish(false) }

    // ---------------- lifecycle ----------------
    private fun begin(c: Context, m: Mode, num: String) {
        if (active) finish(false)
        Store.init(c)
        ctx = c.applicationContext; mode = m; number = num; active = true; answered = false
        lines.clear(); partial = ""; alerted = false; silentErrors = 0; errorStreak = 0
        showOverlay()
        val hints = Engine.numberHints(num).joinToString(" · ") { it.get(kn) }
        tTitle?.text = s("Unknown number", "ಅಪರಿಚಿತ ಸಂಖ್ಯೆ") + "  " + num.ifBlank { s("(hidden)", "(ಮರೆಮಾಚಲಾಗಿದೆ)") }
        status(s("Not in your contacts. Echchara will check this call when you answer. Turn on speaker for best results.", "ನಿಮ್ಮ ಸಂಪರ್ಕಗಳಲ್ಲಿ ಇಲ್ಲ. ಉತ್ತರಿಸಿದಾಗ ಎಚ್ಚರ ಈ ಕರೆಯನ್ನು ಪರಿಶೀಲಿಸುತ್ತದೆ. ಉತ್ತಮ ಫಲಿತಾಂಶಕ್ಕೆ ಸ್ಪೀಕರ್ ಆನ್ ಮಾಡಿ.") + if (hints.isNotEmpty()) "\n⚠ $hints" else "")
        updateRisk()
        try { ContextCompat.startForegroundService(c, Intent(c, CallGuardService::class.java).putExtra("number", num)) } catch (e: Exception) { }
        if (m == Mode.CALL) PayWatch.ensure(c) // pause payment and screen-sharing apps during the call
    }

    /** SMS Shield calls this when an OTP arrives during a call from an unknown number. */
    fun otpArrived() = main.post {
        val c = ctx ?: return@post
        if (!inUnknownCall) return@post
        val warn = Tips.OTP_ALERT.get(kn)
        tReasons?.text = if (alertBox?.visibility == View.VISIBLE) warn + "\n\n" + (tReasons?.text ?: "") else warn
        alertBox?.visibility = View.VISIBLE
        (root?.background as? GradientDrawable)?.setStroke(dp(3), Color.parseColor("#D21F3C"))
        vibrate()
        Store.notifyAlert(c, 7003, Tips.OTP_TITLE.get(kn), warn)
    }

    private fun finish(showSummary: Boolean) {
        if (!active) return
        stopListening(); unwatchCallState()
        val c = ctx
        val text = lines.joinToString("\n")
        if (c != null && text.isNotBlank() && mode != Mode.SAMPLE) {
            val a = Engine.analyze(text)
            Store.addHistory(HistoryItem(System.currentTimeMillis(), "call", number, text, a.score, a.type?.en, a.type?.kn))
            if (a.score >= 30) Store.notifyAlert(c, 7001, s("Call from $number looked like fraud", "$number ಕರೆ ವಂಚನೆಯಂತೆ ಕಂಡಿತು"),
                a.summary.get(kn) + (Tips.codeWord(a)?.let { " " + it.get(kn) } ?: ""), Links.reportActions(c, 900_000, "$number\n$text"))
            if (mode == Mode.CALL && a.score >= 60) RiskWindow.start(c, Txt("a likely fraud call", "ವಂಚನೆಯ ಕರೆ"))
            if (a.score >= 30 && a.flags.any { it.id == "esim" }) Store.simRiskAt = System.currentTimeMillis()
        }
        active = false
        c?.let { try { it.stopService(Intent(it, CallGuardService::class.java)) } catch (e: Exception) { } }
        if (showSummary && root != null && text.isNotBlank()) {
            val a = Engine.analyze(text)
            alertBox?.visibility = View.GONE; listenBtn?.visibility = View.GONE
            verdict(a, true)
            status(s("Call ended", "ಕರೆ ಮುಗಿದಿದೆ") + " · " + a.band.label.get(kn) + " (" + a.score + "/100)\n" + a.summary.get(kn))
            main.postDelayed({ if (!active) removeOverlay() }, 15000)
        } else if (showSummary && root != null && mode == Mode.CALL) {
            alertBox?.visibility = View.GONE; listenBtn?.visibility = View.GONE
            status(s("Call ended. Echchara could not hear the caller (some phones block other apps from hearing calls). If your phone recorded this call, check the recording now.",
                     "ಕರೆ ಮುಗಿದಿದೆ. ಕರೆ ಮಾಡಿದವರ ಧ್ವನಿ ಎಚ್ಚರಕ್ಕೆ ಕೇಳಲಿಲ್ಲ (ಕೆಲವು ಫೋನ್‌ಗಳು ಕರೆಯನ್ನು ಬೇರೆ ಆ್ಯಪ್‌ಗಳಿಗೆ ಕೇಳಿಸುವುದಿಲ್ಲ). ಈ ಕರೆ ರೆಕಾರ್ಡ್ ಆಗಿದ್ದರೆ, ಈಗ ಅದನ್ನು ಪರಿಶೀಲಿಸಿ."))
            recBtn?.visibility = View.VISIBLE; tRisk?.visibility = View.GONE; bar?.visibility = View.GONE
            main.postDelayed({ if (!active) removeOverlay() }, 30000)
        } else removeOverlay()
    }

    // ---------------- call state ----------------
    private fun watchCallState() {
        val c = ctx ?: return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            status(s("Phone permission is off, so Echchara can't tell when you answer. Tap Listen after answering.", "ಫೋನ್ ಅನುಮತಿ ಇಲ್ಲ. ಉತ್ತರಿಸಿದ ನಂತರ Listen ಒತ್ತಿ."))
            return
        }
        val tm = c.getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { main.post { onCallState(state) } }
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(c), cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { main.post { onCallState(state) } }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = l
            }
        } catch (e: SecurityException) { }
    }

    private fun unwatchCallState() {
        val c = ctx ?: return
        val tm = c.getSystemService(TelephonyManager::class.java)
        val cb = telephonyCallback ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is PhoneStateListener) @Suppress("DEPRECATION") tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) { }
        telephonyCallback = null
    }

    private fun onCallState(state: Int) {
        if (!active || mode != Mode.CALL) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!answered) {
                answered = true
                status(s("Checking this call. Turn on speaker so Echchara can hear the caller.", "ಈ ಕರೆಯನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದೆ. ಕರೆ ಮಾಡಿದವರು ಕೇಳಿಸಲು ಸ್ಪೀಕರ್ ಆನ್ ಮಾಡಿ."))
                main.postDelayed({ if (active) startListening() }, 800)
            }
            TelephonyManager.CALL_STATE_IDLE -> finish(answered)
        }
    }

    // ---------------- speech to text ----------------
    private fun startListening() {
        val c = ctx ?: return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status(s("Microphone permission is off. Open Echchara to turn it on.", "ಮೈಕ್ರೊಫೋನ್ ಅನುಮತಿ ಇಲ್ಲ. ಎಚ್ಚರ ತೆರೆದು ಆನ್ ಮಾಡಿ.")); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            status(s("Speech recognition is not available on this phone. Install or update the Google app.", "ಈ ಫೋನ್‌ನಲ್ಲಿ ಧ್ವನಿ ಗುರುತಿಸುವಿಕೆ ಇಲ್ಲ. Google ಆ್ಯಪ್ ಅಪ್‌ಡೇಟ್ ಮಾಡಿ.")); return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(c).apply { setRecognitionListener(listener) }
        }
        listening = true
        listenOnce()
    }

    private fun listenOnce() {
        if (!active || !listening) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (kn) "kn-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try { recognizer?.startListening(i) } catch (e: Exception) { main.postDelayed({ listenOnce() }, 600) }
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
    }

    private val listener: RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(b: Bundle?) {
            partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            renderTranscript()
        }
        override fun onResults(b: Bundle?) {
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            partial = ""
            errorStreak = 0
            if (t.isNotEmpty()) { silentErrors = 0; addLine(t) } else renderTranscript()
            main.postDelayed({ listenOnce() }, 150)
        }
        override fun onError(error: Int) {
            partial = ""
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { status(s("Microphone permission is off.", "ಮೈಕ್ರೊಫೋನ್ ಅನುಮತಿ ಇಲ್ಲ.")); return }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    silentErrors++
                    if (silentErrors == 3 && lines.isEmpty()) status(s("Can't hear the caller yet. Turn on speaker and keep the phone near you. If your phone records calls, you can check the recording after the call.", "ಕರೆ ಮಾಡಿದವರು ಕೇಳಿಸುತ್ತಿಲ್ಲ. ಸ್ಪೀಕರ್ ಆನ್ ಮಾಡಿ. ಫೋನ್ ಕರೆ ರೆಕಾರ್ಡ್ ಮಾಡಿದರೆ, ಕರೆಯ ನಂತರ ರೆಕಾರ್ಡಿಂಗ್ ಪರಿಶೀಲಿಸಬಹುದು."))
                }
            }
            val hard = error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (hard) errorStreak++ else errorStreak = 0
            if (hard && errorStreak == 4) status(s("Speech recognition is not responding. Check your internet connection; Echchara will keep trying.", "ಧ್ವನಿ ಗುರುತಿಸುವಿಕೆ ಪ್ರತಿಕ್ರಿಯಿಸುತ್ತಿಲ್ಲ. ಇಂಟರ್ನೆಟ್ ಪರಿಶೀಲಿಸಿ; ಎಚ್ಚರ ಪ್ರಯತ್ನಿಸುತ್ತಲೇ ಇರುತ್ತದೆ."))
            val base = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 800L else 250L
            val delay = if (hard) minOf(5000L, base * (1L shl minOf(errorStreak, 4))) else base
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                try { recognizer?.destroy() } catch (e: Exception) { }
                recognizer = ctx?.let { SpeechRecognizer.createSpeechRecognizer(it).apply { setRecognitionListener(this@Guard.listener) } }
            }
            main.postDelayed({ listenOnce() }, delay)
        }
    }

    // ---------------- analysis ----------------
    private fun addLine(l: String) {
        if (!active) return
        lines.add(l)
        if (answered) status(if (mode == Mode.MIC) s("Listening through the microphone", "ಮೈಕ್ರೊಫೋನ್ ಮೂಲಕ ಆಲಿಸುತ್ತಿದೆ") else s("Checking this call", "ಈ ಕರೆಯನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದೆ"))
        renderTranscript(); updateRisk()
    }

    private fun updateRisk() {
        val text = lines.joinToString("\n")
        if (text.isBlank()) { tRisk?.text = s("Call risk: no fraud signs yet", "ಕರೆಯ ಅಪಾಯ: ಇನ್ನೂ ಲಕ್ಷಣಗಳಿಲ್ಲ"); bar?.progress = 2; tint(Color.parseColor("#12B76A")); tVerdict?.visibility = View.GONE; return }
        val a = Engine.analyze(text)
        val col = when (a.band) { Band.DANGER -> "#D21F3C"; Band.WARN -> "#A6590B"; else -> "#13753F" }
        tRisk?.text = s("Call risk", "ಕರೆಯ ಅಪಾಯ") + ": " + a.score + "/100" + (a.type?.let { " · " + it.get(kn) } ?: "")
        bar?.progress = maxOf(2, a.score); tint(Color.parseColor(col))
        verdict(a, false)
        if (!alerted && Engine.shouldAlertCall(a)) showAlert(a)
    }

    /** Big SCAM / SUSPICIOUS / SAFE word on the pop-up. */
    private fun verdict(a: Analysis, ended: Boolean) {
        val v = tVerdict ?: return
        val (word, col) = when (a.band) {
            Band.DANGER -> s("🔴 SCAM CALL", "🔴 ವಂಚನೆ ಕರೆ") to "#D21F3C"
            Band.WARN -> s("🟠 SUSPICIOUS – be careful", "🟠 ಸಂಶಯಾಸ್ಪದ – ಎಚ್ಚರ") to "#A6590B"
            else -> (if (ended) s("🟢 SAFE – no scam signs", "🟢 ಸುರಕ್ಷಿತ – ವಂಚನೆಯ ಲಕ್ಷಣಗಳಿಲ್ಲ") else s("🟢 SAFE so far", "🟢 ಸದ್ಯಕ್ಕೆ ಸುರಕ್ಷಿತ")) to "#13753F"
        }
        v.text = word; v.setTextColor(Color.parseColor(col)); v.visibility = View.VISIBLE
    }

    private fun openRecordingCheck() {
        val c = ctx ?: return
        try { c.startActivity(Intent(c, MainActivity::class.java).putExtra("pickRecording", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { }
        removeOverlay()
    }

    private fun showAlert(a: Analysis) {
        alerted = true
        val reasons = a.flags.filter { it.sev == Sev.HIGH || it.sev == Sev.MID }.take(3).joinToString("\n") { "• " + it.label.get(kn) }
        tReasons?.text = s("Likely fraud call", "ಬಹುಶಃ ವಂಚನೆಯ ಕರೆ") + (a.org?.let { " · " + s("posing as ", "ನಟನೆ: ") + it } ?: "") + "\n" + reasons + "\n" +
            s("Do not share any OTP, PIN or money. Hang up now.", "OTP, PIN ಅಥವಾ ಹಣ ಕೊಡಬೇಡಿ. ಈಗಲೇ ಕರೆ ಕಡಿತಗೊಳಿಸಿ.") +
            (Tips.codeWord(a)?.let { "\n" + it.get(kn) } ?: "")
        alertBox?.visibility = View.VISIBLE
        (root?.background as? GradientDrawable)?.setStroke(dp(3), Color.parseColor("#D21F3C"))
        vibrate()
        ctx?.let {
            Store.notifyAlert(it, 7000, s("Likely fraud call", "ಬಹುಶಃ ವಂಚನೆಯ ಕರೆ"), a.summary.get(kn))
            // Payment pause for the next 20 minutes. After a sample call this lets you try the pause screen.
            RiskWindow.start(it, when (mode) {
                Mode.SAMPLE -> Txt("the sample scam call", "ಮಾದರಿ ವಂಚನೆ ಕರೆ")
                Mode.MIC -> Txt("a scam warning", "ವಂಚನೆಯ ಎಚ್ಚರಿಕೆ")
                Mode.CALL -> Txt("a likely fraud call", "ವಂಚನೆಯ ಕರೆ")
            })
            if (mode != Mode.SAMPLE && a.flags.any { f -> f.id == "esim" }) Store.simRiskAt = System.currentTimeMillis()
        }
    }

    private fun hangUp() {
        val c = ctx ?: return
        var ended = false
        if (mode == Mode.CALL && ContextCompat.checkSelfPermission(c, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            try { @Suppress("DEPRECATION") ended = c.getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) { }
        }
        if (!ended || mode != Mode.CALL) finish(true)
    }

    private fun vibrate() {
        val c = ctx ?: return
        try { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)) } catch (e: Exception) { }
    }

    // ---------------- overlay ----------------
    private fun dp(v: Int): Int = ctx?.let { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), it.resources.displayMetrics).toInt() } ?: v
    private fun status(t: String) { tStatus?.text = t }
    private fun tint(c: Int) { bar?.progressTintList = android.content.res.ColorStateList.valueOf(c); tRisk?.setTextColor(c) }

    private fun renderTranscript() {
        val shown = lines.takeLast(3).joinToString("\n") { "“$it”" } + if (partial.isNotEmpty()) "\n… $partial" else ""
        tTranscript?.text = shown
        tTranscript?.visibility = if (shown.isBlank()) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val c = ctx ?: return
        if (!Settings.canDrawOverlays(c)) return
        removeOverlay()
        val wm = c.getSystemService(WindowManager::class.java)
        val ink = Color.parseColor("#132A55"); val muted = Color.parseColor("#5B6B86")
        fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(c).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#D0D5DD")) }
            elevation = dp(10).toFloat()
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = tv(12f, Color.parseColor("#132A55"), true).apply { text = "🛡 ಎಚ್ಚರ Echchara Call Shield" }
        val close = tv(20f, muted).apply { text = "×"; setPadding(dp(12), 0, dp(4), 0); setOnClickListener { stop() } }
        head.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); head.addView(close)
        tTitle = tv(16f, ink, true).apply { setPadding(0, dp(6), 0, 0) }
        tStatus = tv(13f, muted).apply { setPadding(0, dp(4), 0, dp(6)) }
        tVerdict = tv(22f, ink, true).apply { visibility = View.GONE; setPadding(0, dp(2), 0, dp(4)) }
        tRisk = tv(13f, ink, true)
        recBtn = Button(c).apply { text = s("📞 Check the call recording", "📞 ಕರೆ ರೆಕಾರ್ಡಿಂಗ್ ಪರಿಶೀಲಿಸಿ"); isAllCaps = false; visibility = View.GONE; setOnClickListener { openRecordingCheck() } }
        bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 2 }
        tTranscript = tv(13f, ink).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = GradientDrawable().apply { setColor(Color.parseColor("#F2F4F7")); cornerRadius = dp(10).toFloat() }; visibility = View.GONE }
        listenBtn = Button(c).apply { text = s("Listen now", "ಈಗ ಆಲಿಸಿ"); isAllCaps = false; setOnClickListener { answered = true; ctx?.let { c -> try { ContextCompat.startForegroundService(c, Intent(c, CallGuardService::class.java).putExtra("number", number)) } catch (e: Exception) { } }; if (!listening) startListening() } }

        alertBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        tReasons = tv(14f, Color.parseColor("#D21F3C"), true)
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val hang = Button(c).apply { text = s("Hang up now", "ಕರೆ ಕಡಿತಗೊಳಿಸಿ"); isAllCaps = false; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#D21F3C")); cornerRadius = dp(10).toFloat() }; setOnClickListener { hangUp() } }
        val keep = Button(c).apply { text = s("Keep listening", "ಆಲಿಸುತ್ತಿರಿ"); isAllCaps = false; setOnClickListener { alertBox?.visibility = View.GONE } }
        row.addView(hang, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }); row.addView(keep, LinearLayout.LayoutParams(0, dp(46), 1f))
        alertBox?.addView(tReasons); alertBox?.addView(row)

        box.addView(head); box.addView(tTitle); box.addView(tStatus); box.addView(tVerdict); box.addView(tRisk)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        box.addView(tTranscript, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (mode == Mode.CALL) box.addView(listenBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)).apply { topMargin = dp(6) })
        if (mode == Mode.CALL) box.addView(recBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        box.addView(alertBox)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(190); horizontalMargin = 0.03f }
        lp = params

        // drag the card up or down by its header
        var startY = 0; var touchY = 0f
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = params.y; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.y = (startY + (e.rawY - touchY)).toInt().coerceAtLeast(0); try { wm.updateViewLayout(box, params) } catch (x: Exception) { }; true }
                else -> false
            }
        }
        try { wm.addView(box, params); root = box } catch (e: Exception) { root = null }
    }

    private fun removeOverlay() {
        val c = ctx ?: return
        root?.let { try { c.getSystemService(WindowManager::class.java).removeView(it) } catch (e: Exception) { } }
        root = null; tTitle = null; tStatus = null; tRisk = null; bar = null; tTranscript = null; alertBox = null; tReasons = null; tVerdict = null; listenBtn = null; recBtn = null
    }
}
