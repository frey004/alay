package com.alay.asistente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Servicio en primer plano que escucha todo el tiempo (sin internet, con Vosk)
 * y abre el avatar al oír la palabra de activación, aunque estés en otra app
 * o la pantalla esté bloqueada.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "AlayWake"
        private const val CH_SERVICE = "alay_service"
        private const val CH_WAKE = "alay_wake"
        private const val NOTIF_ID = 1
        private const val WAKE_NOTIF_ID = 2

        @Volatile var instance: WakeWordService? = null
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WakeWordService::class.java))

        /** El avatar está abierto y necesita el micrófono. */
        fun pause() { instance?.let { s -> s.main.post { s.pauseListening() } } }

        /** El avatar se cerró: volver a esperar la palabra de activación. */
        fun resumeSoon() { instance?.let { s -> s.main.postDelayed({ s.resumeListening() }, 800) } }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(NOTIF_ID, status("Di “${firstWake()}” para hablar conmigo"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, status("Di “${firstWake()}” para hablar conmigo"))
        } catch (e: Exception) {
            // Android no deja iniciar el micrófono en segundo plano (p. ej. tras reiniciar).
            Log.w(TAG, "No se pudo iniciar en primer plano", e)
            stopSelf(); return
        }
        instance = this
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alay:wake").apply { acquire() }

        if (!ModelManager.isReady(this)) {
            notify(status("Falta descargar el modelo de voz. Abre Alay.")); return
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        Thread {
            try {
                val m = Model(ModelManager.dir(this).absolutePath)
                main.post {
                    if (instance == null) { m.close(); return@post }
                    model = m
                    if (!AssistantActivity.isOpen) resumeListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando el modelo", e)
                main.post { notify(status("No se pudo cargar el modelo de voz. Vuelve a descargarlo.")) }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        pauseListening()
        model?.close(); model = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- Micrófono ----------
    private fun resumeListening() {
        val m = model ?: return
        if (speech != null || AssistantActivity.isOpen) return
        try {
            val r = Recognizer(m, 16000f)
            recognizer = r
            speech = SpeechService(r, 16000f).also { it.startListening(this) }
            notify(status("Di “${firstWake()}” para hablar conmigo"))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el micrófono", e)
            pauseListening()
            main.postDelayed({ resumeListening() }, 5000)
        }
    }

    private fun pauseListening() {
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        recognizer?.close(); recognizer = null
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onError(e: Exception?) {
        Log.e(TAG, "Error de reconocimiento", e)
        pauseListening(); main.postDelayed({ resumeListening() }, 2000)
    }
    override fun onTimeout() { pauseListening(); resumeListening() }

    private fun check(json: String?, field: String) {
        if (json == null || speech == null) return
        val text = Text.normalize(JSONObject(json).optString(field))
        if (text.isEmpty()) return
        if (Wake.matches(this, text)) {
            Log.i(TAG, "Palabra de activación: $text")
            pauseListening()
            openAvatar()
        } else if (field == "text") {
            // Frase terminada sin palabra clave: reiniciamos para no acumular texto.
            recognizer?.reset()
        }
    }

    private fun openAvatar() {
        val i = Intent(this, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantActivity.EXTRA_FROM_WAKE, true)
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "startActivity", e) }
        } else {
            // Sin el permiso "mostrar sobre otras apps", Android no deja abrir pantallas
            // desde segundo plano: usamos una notificación de pantalla completa.
            val pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(this, CH_WAKE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(Prefs.name(this))
                .setContentText("Te escucho…")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15000)
                .build()
            getSystemService(NotificationManager::class.java).notify(WAKE_NOTIF_ID, n)
        }
        // Si el avatar no llega a abrirse, volvemos a escuchar.
        main.postDelayed({ if (!AssistantActivity.isOpen) resumeListening() }, 12000)
    }

    // ---------- Notificaciones ----------
    private fun firstWake() = Prefs.wake(this).split(",").first().trim().replaceFirstChar { it.uppercase() }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Escucha en segundo plano",
            NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WAKE, "Activación por voz",
            NotificationManager.IMPORTANCE_HIGH))
    }

    private fun status(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(Prefs.name(this) + " está escuchando")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
}
