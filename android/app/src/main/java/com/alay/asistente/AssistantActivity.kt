package com.alay.asistente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.TextView

/**
 * La pantalla del avatar: aparece encima de todo, escucha lo que dices,
 * responde en voz alta moviendo la boca y se va sola cuando terminas.
 */
class AssistantActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
        @Volatile var isOpen = false
            private set
    }

    private lateinit var avatar: AvatarView
    private lateinit var youSaid: TextView
    private lateinit var reply: TextView
    private lateinit var stateLabel: TextView

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, () -> Unit>? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busy = false
    private var followUp = false
    private var utteranceSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        WakeWordService.pause()
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_assistant)

        avatar = findViewById(R.id.avatar)
        youSaid = findViewById(R.id.youSaid)
        reply = findViewById(R.id.reply)
        stateLabel = findViewById(R.id.stateLabel)
        findViewById<TextView>(R.id.close).setOnClickListener { finish() }
        // Tocar fuera del avatar lo cierra; tocar el avatar vuelve a escuchar.
        findViewById<android.view.View>(R.id.root).setOnClickListener { finish() }
        avatar.setOnClickListener { if (!busy) { stopTalking(); startListening() } }

        tts = TextToSpeech(this, this)
        // Esperamos un instante a que el servicio suelte el micrófono.
        main.postDelayed({ startListening() }, 350)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Volvieron a llamarlo mientras estaba abierto.
        if (!busy) { stopTalking(); followUp = false; startListening() }
    }

    override fun onStop() {
        super.onStop()
        // Si el usuario se va a otra app (o abrimos una), cerramos el avatar.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        isOpen = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        avatar.speaking = false
        WakeWordService.resumeSoon()
        super.onDestroy()
    }

    // ---------- Estado visual ----------
    private fun setState(s: AvatarView.State) {
        avatar.state = s
        stateLabel.text = when (s) {
            AvatarView.State.LISTENING -> "Escuchando…"
            AvatarView.State.THINKING -> "Pensando…"
            else -> ""
        }
    }

    // ---------- Escuchar ----------
    private fun startListening() {
        if (isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Necesito permiso para usar el micrófono. Ábreme desde el icono de Alay para darlo.") { finish() }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Este teléfono no tiene reconocimiento de voz. Instala o actualiza la app de Google.") { finish() }
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Text.spanish().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        setState(AvatarView.State.LISTENING)
        if (!followUp) { youSaid.text = ""; reply.text = "" }
        listening = true
        sr.cancel()
        sr.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { youSaid.text = it }
        }

        override fun onResults(b: Bundle?) {
            listening = false
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) noInput() else handle(text)
        }

        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                main.postDelayed({ startListening() }, 400); return
            }
            noInput()
        }
    }

    private fun noInput() {
        if (followUp) { finish(); return }
        setState(AvatarView.State.IDLE)
        reply.text = "No te escuché. Toca el avatar para intentarlo otra vez."
        main.postDelayed({ if (!listening && !busy) finish() }, 5000)
    }

    // ---------- Responder ----------
    private fun handle(raw: String) {
        val cmd = Wake.strip(this, Text.normalize(raw))
        youSaid.text = raw
        if (cmd.isBlank()) { startListening(); return }   // solo dijo "Oye Alay"
        busy = true
        setState(AvatarView.State.THINKING)

        val local = Brain.local(this, cmd)
        when {
            local != null -> deliver(local)
            Prefs.key(this).isBlank() -> deliver(Brain.fallback())
            else -> Thread {
                val answer = try { Brain.askClaude(this, raw) } catch (e: Exception) {
                    "No pude conectarme. Revisa tu conexión a internet."
                }
                main.post { if (!isFinishing) deliver(Reply(answer)) }
            }.start()
        }
    }

    private fun deliver(r: Reply) {
        if (r.close) { finish(); return }
        speak(r.text) {
            busy = false
            val action = r.action
            if (action != null) {
                try { action(this) } catch (_: Exception) { }
                finish()
            } else {
                // Conversación continua: sigue escuchando por si quieres algo más.
                followUp = true
                startListening()
            }
        }
    }

    // ---------- Hablar ----------
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val loc = Text.spanish()
            if (t.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) t.language = java.util.Locale("es")
            else t.language = loc
            t.setSpeechRate(1.03f)
            t.setPitch(1.1f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { avatar.speaking = true }
                override fun onDone(id: String?) = finished(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = finished(id)
                override fun onError(id: String?, errorCode: Int) = finished(id)
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { avatar.pulse() }
            })
            ttsReady = true
        }
        pendingSpeech?.let { (text, done) -> pendingSpeech = null; speak(text, done) }
    }

    private var onSpeechDone: (() -> Unit)? = null

    private fun finished(id: String?) {
        main.post {
            if (id != utteranceSeq.toString()) return@post
            avatar.speaking = false
            onSpeechDone?.also { onSpeechDone = null }?.invoke()
        }
    }

    private fun speak(text: String, done: () -> Unit) {
        setState(AvatarView.State.SPEAKING)
        reply.text = text
        val t = tts
        if (t == null || !ttsReady) {
            if (t != null && pendingSpeech == null && !ttsReadyFailed()) { pendingSpeech = text to done; return }
            // Sin voz: animamos la boca un rato y seguimos.
            avatar.speaking = true
            main.postDelayed({ avatar.speaking = false; done() }, 1500L + text.length * 50L)
            return
        }
        utteranceSeq++
        val id = utteranceSeq.toString()
        onSpeechDone = done
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        // Por si el motor de voz nunca avisa de que terminó.
        main.postDelayed({ if (utteranceSeq.toString() == id && onSpeechDone != null) finished(id) },
            4000L + text.length * 120L)
    }

    private var initStarted = System.currentTimeMillis()
    private fun ttsReadyFailed() = System.currentTimeMillis() - initStarted > 5000

    private fun stopTalking() {
        utteranceSeq++
        onSpeechDone = null
        tts?.stop()
        avatar.speaking = false
    }
}
