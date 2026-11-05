package com.alay.asistente

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Lo que responde Alay.
 * @param action se ejecuta después de hablar (abrir una app, poner un temporizador…) y luego se cierra el avatar.
 * @param close  cerrar sin decir nada ("adiós", "nada").
 */
data class Reply(val text: String, val action: ((Activity) -> Unit)? = null, val close: Boolean = false)

object Brain {
    private val jokes = listOf(
        "¿Qué le dice un bit al otro? Nos vemos en el bus.",
        "¿Por qué el libro de matemáticas estaba triste? Porque tenía demasiados problemas.",
        "¿Cuál es el colmo de un robot? Tener un corazón de lata y que se le oxide.",
        "Le pregunté a mi wifi si me quería. Me dijo: depende de dónde te sientes.",
        "¿Qué hace una abeja en el gimnasio? Zum-ba.",
    )

    private val apps = mapOf(
        "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps", "mapas" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps", "instagram" to "com.instagram.android",
        "gmail" to "com.google.android.gm", "correo" to "com.google.android.gm",
        "spotify" to "com.spotify.music", "chrome" to "com.android.chrome",
        "tiktok" to "com.zhiliaoapp.musically", "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger", "netflix" to "com.netflix.mediaclient",
        "fotos" to "com.google.android.apps.photos", "play store" to "com.android.vending",
    )

    private val numberWords = mapOf("un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
        "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11,
        "doce" to 12, "quince" to 15, "veinte" to 20, "treinta" to 30, "media" to 30)

    private fun num(s: String): Int? = s.toIntOrNull() ?: numberWords[s]

    /** Comandos que funcionan sin internet ni clave. Devuelve null si no lo entiende. */
    fun local(ctx: Context, c: String): Reply? {
        fun has(vararg w: String) = w.any { c.contains(it) }
        val now = Date()
        val es = Text.spanish()

        if (Regex("^(nada|adios|chao|hasta luego|cierra(te)?|olvidalo|cancela(r)?|ya esta|eso es todo)$").matches(c))
            return Reply("", close = true)
        if (has("que hora")) return Reply("Son las ${SimpleDateFormat("H:mm", es).format(now)}.")
        if (has("que dia", "que fecha", "fecha de hoy"))
            return Reply("Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es).format(now)}.")
        if (has("chiste")) return Reply(jokes.random())
        if (has("como te llamas", "quien eres")) return Reply("Soy ${Prefs.name(ctx)}, tu asistente de voz.")
        if (Regex("^(hola|buenas|buenos dias|buenas tardes|buenas noches)\\b.*").matches(c) && c.split(" ").size <= 3)
            return Reply("¡Hola! ¿En qué te ayudo?")
        if (has("gracias")) return Reply("¡De nada! Para eso estoy.")
        if (has("como estas")) return Reply("¡Muy bien, gracias! Lista para ayudarte.")

        // Linterna
        if (has("linterna")) {
            val on = !has("apaga", "quita")
            return Reply(if (on) "Linterna encendida." else "Linterna apagada.", action = { torch(it, on) })
        }

        // Temporizador: "pon un temporizador de 5 minutos"
        Regex("temporizador de (\\w+) (segundo|minuto|hora)").find(c)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            val unit = m.groupValues[2]
            val secs = n * when (unit) { "segundo" -> 1; "minuto" -> 60; else -> 3600 }
            return Reply("Listo, temporizador de $n $unit${if (n == 1) "" else "s"}.", action = {
                it.tryStart(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, Prefs.name(it))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            })
        }

        // Alarma: "pon una alarma a las 7:30 de la mañana"
        Regex("alarma (?:a|para) las (\\w+)(?::(\\d{2})| y (\\w+))?(?: (de la manana|de la tarde|de la noche|am|pm))?").find(c)?.let { m ->
            var h = num(m.groupValues[1]) ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: num(m.groupValues[3]) ?: 0
            val part = m.groupValues[4]
            if ((part == "de la tarde" || part == "de la noche" || part == "pm") && h < 12) h += 12
            if (h > 23 || min > 59) return@let
            val label = String.format("%d:%02d", h, min)
            return Reply("Alarma puesta a las $label.", action = {
                it.tryStart(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, Prefs.name(it))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            })
        }

        // Música / vídeos: "pon música de Bad Bunny"
        Regex("^(?:pon|ponme|reproduce|quiero escuchar|quiero ver)\\s+(.+)").find(c)?.let { m ->
            val q = m.groupValues[1].replace(Regex("^(musica de|la cancion|una cancion de|canciones de|un video de|el video de)\\s+"), "")
            return Reply("Buscando $q en YouTube.", action = { a ->
                val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
                if (!a.tryStart(yt)) a.tryStart(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            })
        }

        // Búsqueda: "busca recetas de pasta"
        Regex("^(?:busca|buscar|googlea|busca en google)\\s+(.+)").find(c)?.let { m ->
            val q = m.groupValues[1]
            return Reply("Buscando $q.", action = { a ->
                if (!a.tryStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)))
                    a.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            })
        }

        // Abrir apps: "abre WhatsApp"
        Regex("^(?:abre|abrir|abreme|lanza|inicia)\\s+(?:la app de |la aplicacion de |el |la )?(.+)").find(c)?.let { m ->
            val name = m.groupValues[1].trim()
            val intent = appIntent(ctx, name) ?: return Reply("No encontré la app $name en tu teléfono.")
            return Reply("Abriendo $name.", action = { it.tryStart(intent) })
        }

        // Cuentas: "cuánto es 12 por 8"
        Regex("cuanto es (-?[\\d.,]+) (mas|menos|por|x|entre|dividido entre|dividido por) (-?[\\d.,]+)").find(c)?.let { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val b = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@let
            val r = when (m.groupValues[2]) {
                "mas" -> a + b; "menos" -> a - b; "por", "x" -> a * b
                else -> if (b == 0.0) return Reply("No se puede dividir entre cero.") else a / b
            }
            val txt = if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString()
                else String.format(es, "%.4f", r).trimEnd('0').trimEnd(',', '.')
            return Reply("Son $txt.")
        }
        return null
    }

    fun fallback() = Reply("Todavía no sé hacer eso. Puedo decirte la hora, poner alarmas y temporizadores, " +
        "abrir apps, poner música o buscar en internet. Si añades una clave de API en ajustes, " +
        "podré responder a cualquier pregunta.")

    private fun appIntent(ctx: Context, spoken: String): Intent? {
        val pm = ctx.packageManager
        when (spoken) {
            "camara", "la camara" -> return Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "ajustes", "configuracion" -> return Intent(Settings.ACTION_SETTINGS)
        }
        apps[spoken]?.let { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { return it } }
        // Buscar entre las apps instaladas por su nombre.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(launcher, 0)
        val match = all.firstOrNull { Text.normalize(it.loadLabel(pm).toString()) == spoken }
            ?: all.firstOrNull { Text.normalize(it.loadLabel(pm).toString()).contains(spoken) }
            ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private fun torch(ctx: Context, on: Boolean) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        try { cm.setTorchMode(id, on) } catch (_: Exception) { }
    }

    // ---------- IA (opcional) ----------
    private val history = mutableListOf<JSONObject>()

    /** Llamada bloqueante: ejecutar en un hilo aparte. */
    fun askClaude(ctx: Context, text: String): String {
        history += JSONObject().put("role", "user").put("content", text)
        while (history.size > 12) history.removeAt(0)
        val system = "Eres ${Prefs.name(ctx)}, un asistente de voz amable que habla español. " +
            "Tus respuestas se leen en voz alta: sé breve (1 a 3 frases), natural y sin markdown, " +
            "listas, emojis ni enlaces. Fecha y hora actual: ${Date()}."
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 400)
            .put("system", system)
            .put("messages", JSONArray(history))

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 30000; doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", Prefs.key(ctx))
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                history.removeAt(history.size - 1)
                return if (code == 401) "La clave de API no es válida. Revísala en los ajustes."
                else "El servicio de inteligencia artificial respondió con un error $code."
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val content = json.getJSONArray("content")
            val out = (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString(" ") { it.getString("text") }.trim()
            history += JSONObject().put("role", "assistant").put("content", out)
            return out.ifEmpty { "No tengo respuesta para eso." }
        } catch (e: Exception) {
            if (history.lastOrNull()?.optString("role") == "user") history.removeAt(history.size - 1)
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

/** Abre un Intent sin que la app se cierre si no hay nada que lo pueda abrir. */
fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
