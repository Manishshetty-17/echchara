package org.echchara.app

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Voice help: reads results, screens and scam warnings aloud in Kannada or English,
 * for people who find reading hard. Uses the phone's own text-to-speech, so nothing leaves the phone.
 */
object Voice {
    private val KANNADA = Locale("kn", "IN")
    private val ENGLISH = Locale("en", "IN")
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()

    /** Compose-observable state for the UI. */
    val speaking = mutableStateOf(false)
    val kannadaReady = mutableStateOf(true)

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking.value = true }
                    override fun onDone(id: String?) { if (id == "last") speaking.value = false }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking.value = false }
                })
                val r = tts?.isLanguageAvailable(KANNADA) ?: TextToSpeech.LANG_NOT_SUPPORTED
                kannadaReady.value = r >= TextToSpeech.LANG_AVAILABLE
                synchronized(pending) { pending.forEach { it() }; pending.clear() }
            }
        }
    }

    /** Speak [en] or [kn]. If the phone has no Kannada voice, the English text is read instead. */
    fun say(ctx: Context, en: String, kn: String, kannada: Boolean = Store.kannada, onDone: (() -> Unit)? = null) {
        init(ctx)
        val run = {
            val t = tts
            if (t != null) {
                val useKn = kannada && kannadaReady.value
                t.language = if (useKn) KANNADA else ENGLISH
                t.setSpeechRate(0.9f)
                val text = if (useKn) kn else en
                val parts = text.split(Regex("(?<=[.!?।])\\s+")).filter { it.isNotBlank() }
                parts.forEachIndexed { i, p ->
                    t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i")
                }
                if (onDone != null) Thread { while (t.isSpeaking || speaking.value) Thread.sleep(200); onDone() }.start()
            }
        }
        if (ready) run() else synchronized(pending) { pending.add(run) }
    }

    fun stop() { tts?.stop(); speaking.value = false }

    /** Speak and wait (used from the SMS receiver, which has about 10 seconds). */
    fun sayAndWait(ctx: Context, en: String, kn: String, seconds: Long = 9) {
        val latch = CountDownLatch(1)
        say(ctx, en, kn) { latch.countDown() }
        latch.await(seconds, TimeUnit.SECONDS)
    }

    /** The result as one spoken paragraph: verdict, scam type, the main reasons and what to do. */
    fun resultText(a: Analysis, kannada: Boolean): String {
        val verdict = when (a.band) {
            Band.DANGER -> Txt("Danger. This is very likely a scam.", "ಅಪಾಯ. ಇದು ಬಹುತೇಕ ವಂಚನೆ.")
            Band.WARN -> Txt("Be careful. This looks suspicious.", "ಎಚ್ಚರಿಕೆ. ಇದು ಅನುಮಾನಾಸ್ಪದವಾಗಿ ಕಾಣುತ್ತದೆ.")
            Band.SAFE -> Txt("Looks safe.", "ಸುರಕ್ಷಿತವಾಗಿ ಕಾಣುತ್ತದೆ.")
        }.get(kannada)
        val type = a.type?.let { (if (kannada) "ವಂಚನೆಯ ವಿಧ: " else "Type of scam: ") + it.get(kannada) + "." } ?: ""
        val reasons = a.flags.filter { it.sev == Sev.HIGH || it.sev == Sev.MID }.take(3).joinToString(". ") { it.label.get(kannada) }
        val reasonText = if (reasons.isNotEmpty()) (if (kannada) "ಎಚ್ಚರಿಕೆಯ ಲಕ್ಷಣಗಳು: " else "Warning signs: ") + reasons + "." else ""
        val todo = a.todos.take(2).joinToString(" ") { it.get(kannada) }
        val todoText = if (todo.isNotEmpty() && a.band != Band.SAFE) (if (kannada) "ಏನು ಮಾಡಬೇಕು: " else "What to do: ") + todo
            else if (a.band == Band.SAFE) Txt("If money or a code is involved, confirm with the sender on a number you already know.", "ಹಣ ಅಥವಾ ಕೋಡ್ ವಿಷಯವಿದ್ದರೆ, ನಿಮಗೆ ತಿಳಿದ ಸಂಖ್ಯೆಯ ಮೂಲಕ ಖಚಿತಪಡಿಸಿ.").get(kannada) else ""
        return listOf(verdict, type, reasonText, todoText).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun sayResult(ctx: Context, a: Analysis) = say(ctx, resultText(a, false), resultText(a, true))

    val CHECK_GUIDE = Txt(
        "This is the check screen. Paste a message in the box, or press Speak the message and say it aloud. Then press Check. Green means it looks safe. Orange means be careful. Red means it is a scam. Press Listen to hear the result.",
        "ಇದು ಪರಿಶೀಲನೆಯ ಪರದೆ. ಪೆಟ್ಟಿಗೆಯಲ್ಲಿ ಸಂದೇಶ ಅಂಟಿಸಿ, ಅಥವಾ ಸಂದೇಶ ಹೇಳಿ ಬಟನ್ ಒತ್ತಿ ಜೋರಾಗಿ ಹೇಳಿ. ನಂತರ ಪರಿಶೀಲಿಸಿ ಒತ್ತಿ. ಹಸಿರು ಎಂದರೆ ಸುರಕ್ಷಿತ. ಕಿತ್ತಳೆ ಎಂದರೆ ಎಚ್ಚರಿಕೆ. ಕೆಂಪು ಎಂದರೆ ವಂಚನೆ. ಫಲಿತಾಂಶ ಕೇಳಲು ಕೇಳಿ ಬಟನ್ ಒತ್ತಿ.")
    val PROTECT_GUIDE = Txt(
        "This is the protection screen. Echchara checks calls from unknown numbers, every SMS, and new apps on this phone. Press Turn on for each shield until it says You are protected. If you lose money to a scam, call 1930 immediately.",
        "ಇದು ರಕ್ಷಣೆಯ ಪರದೆ. ಎಚ್ಚರ ಅಪರಿಚಿತ ಕರೆಗಳು, ಪ್ರತಿ SMS ಮತ್ತು ಈ ಫೋನ್‌ನ ಹೊಸ ಆ್ಯಪ್‌ಗಳನ್ನು ಪರಿಶೀಲಿಸುತ್ತದೆ. ನೀವು ರಕ್ಷಿತರಾಗಿದ್ದೀರಿ ಎಂದು ಬರುವವರೆಗೆ ಪ್ರತಿ ರಕ್ಷಣೆಗೆ ಆನ್ ಮಾಡಿ ಒತ್ತಿ. ವಂಚನೆಯಲ್ಲಿ ಹಣ ಕಳೆದುಕೊಂಡರೆ ತಕ್ಷಣ ಒಂದು ಒಂಬತ್ತು ಮೂರು ಸೊನ್ನೆಗೆ ಕರೆ ಮಾಡಿ.")

    /** The phone's own speech-to-text screen, in Kannada or English. */
    fun listenIntent(kannada: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (kannada) "kn-IN" else "en-IN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, if (kannada) "ಸಂದೇಶವನ್ನು ಜೋರಾಗಿ ಹೇಳಿ" else "Say the message aloud")
    }

    fun installKannadaIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
}
