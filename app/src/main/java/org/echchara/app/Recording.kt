package org.echchara.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

/**
 * Turns a saved call recording into text so the scam engine can judge it.
 *  1. Android 13+: the audio is decoded on the phone and handed straight to the speech recogniser (nothing is played).
 *  2. Otherwise, or if that gives no words: the recording is played on the speaker while the microphone listens.
 * The recording never leaves the phone except through the phone's own speech service.
 */
class RecordingCheck(
    private val ctx: Context,
    private val uri: Uri,
    private val kannada: Boolean,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onDone: (String) -> Unit
) {
    private val h = Handler(Looper.getMainLooper())
    private val heard = StringBuilder()
    private var partial = ""
    @Volatile private var active = false
    private var sr: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var dictation: Dictation? = null
    private fun s(en: String, kn: String) = if (kannada) kn else en

    fun start() {
        active = true
        onStatus(s("Reading the recording…", "ರೆಕಾರ್ಡಿಂಗ್ ಓದುತ್ತಿದೆ…"))
        if (Build.VERSION.SDK_INT >= 33) Thread {
            val pcm = try { decode16k() } catch (e: Exception) { null }
            h.post { if (active) { if (pcm != null && pcm.size > 3200) direct(pcm) else playAloud() } }
        }.start() else playAloud()
    }

    fun cancel() {
        active = false
        try { sr?.cancel(); sr?.destroy() } catch (e: Exception) { }
        sr = null
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        player = null
        dictation?.cancel(); dictation = null
    }

    private fun current() = (heard.toString() + " " + partial).trim()

    private fun finish() {
        if (!active) return
        val text = current()
        cancel()
        onDone(text)
    }

    // ---------- 1. decode the file to 16 kHz mono PCM and feed it to the recogniser ----------
    private fun decode16k(): ByteArray {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = ArrayList<Short>(1 shl 20)
        val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
        val maxSamples = 16000 * 600 // 10 minutes is plenty for a call
        while (!outDone && active) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = b.remaining() / ch
                for (f in 0 until frames) { var sum = 0; for (c in 0 until ch) sum += b.get(f * ch + c); mono.add((sum / ch).toShort()) }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                if (mono.size.toLong() * 16000 / rate > maxSamples) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        // resample to 16 kHz (linear)
        val outN = (mono.size.toLong() * 16000 / rate).toInt()
        val out = ByteArrayOutputStream(outN * 2)
        for (k in 0 until outN) {
            val pos = k.toDouble() * rate / 16000; val i0 = pos.toInt(); val fr = pos - i0
            val a = mono[minOf(i0, mono.size - 1)]; val b2 = mono[minOf(i0 + 1, mono.size - 1)]
            val v = (a + (b2 - a) * fr).toInt()
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
        }
        return out.toByteArray()
    }

    private fun direct(pcm: ByteArray) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(ctx)) { playAloud(); return }
        onStatus(s("Listening to the recording…", "ರೆಕಾರ್ಡಿಂಗ್ ಆಲಿಸುತ್ತಿದೆ…"))
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            try { FileOutputStream(pipe[1].fileDescriptor).use { o -> var p = 0; while (p < pcm.size && active) { val n = minOf(6400, pcm.size - p); o.write(pcm, p, n); p += n; Thread.sleep(170) } } } catch (e: Exception) { }
            try { pipe[1].close() } catch (e: Exception) { }
        }.start()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (kannada) "kn-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        var gotAny = false
        val timeout = Runnable { if (active) { if (current().isNotBlank()) finish() else { stopRecognizer(); playAloud() } } }
        h.postDelayed(timeout, 20_000L + pcm.size / 32)   // audio length (fed at about real time) + 20 s
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) { partial = t; onText(current()) }
                }
                private fun take(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (t.isNotEmpty()) { gotAny = true; if (heard.isNotEmpty()) heard.append(' '); heard.append(t) }
                    partial = ""; onText(current())
                }
                override fun onSegmentResults(b: Bundle) = take(b)
                override fun onEndOfSegmentedSession() { h.removeCallbacks(timeout); if (gotAny || current().isNotBlank()) finish() else { stopRecognizer(); playAloud() } }
                override fun onResults(b: Bundle?) { take(b); h.removeCallbacks(timeout); if (current().isNotBlank()) finish() else { stopRecognizer(); playAloud() } }
                override fun onError(e: Int) { h.removeCallbacks(timeout); if (current().isNotBlank()) finish() else { stopRecognizer(); playAloud() } }
            })
            try { startListening(i) } catch (e: Exception) { h.removeCallbacks(timeout); stopRecognizer(); playAloud() }
        }
    }

    private fun stopRecognizer() { try { sr?.cancel(); sr?.destroy() } catch (e: Exception) { }; sr = null }

    // ---------- 2. fallback: play it on the speaker and listen with the microphone ----------
    private fun playAloud() {
        if (!active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { finish(); return }
        onStatus(s("Playing the recording aloud so Echchara can hear it. Keep the volume up.", "ಎಚ್ಚರ ಕೇಳಲು ರೆಕಾರ್ಡಿಂಗ್ ಜೋರಾಗಿ ಪ್ಲೇ ಆಗುತ್ತಿದೆ. ವಾಲ್ಯೂಮ್ ಹೆಚ್ಚಿಸಿ."))
        val d = Dictation(ctx, kannada, onText = { t -> partial = ""; heard.setLength(0); heard.append(t); onText(t) }, onDone = { t -> heard.setLength(0); heard.append(t); finish() })
        dictation = d; d.start()
        h.postDelayed({
            if (!active) return@postDelayed
            try {
                player = MediaPlayer().apply {
                    setDataSource(ctx, uri)
                    setOnCompletionListener { h.postDelayed({ dictation?.finish() }, 2500) }
                    prepare(); start()
                }
            } catch (e: Exception) { dictation?.finish() }
        }, 700)
    }
}
