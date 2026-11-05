package com.alay.asistente

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** Ajustes guardados en el teléfono. */
object Prefs {
    const val DEFAULT_WAKE = "oye alay, hey alay, ok alay, hola alay"
    private fun sp(c: Context) = c.getSharedPreferences("alay", Context.MODE_PRIVATE)

    fun wake(c: Context) = sp(c).getString("wake", DEFAULT_WAKE)!!.ifBlank { DEFAULT_WAKE }
    fun name(c: Context) = sp(c).getString("name", "Alay")!!.ifBlank { "Alay" }
    fun key(c: Context) = sp(c).getString("key", "")!!
    fun alwaysListen(c: Context) = sp(c).getBoolean("listen", false)

    fun save(c: Context, wake: String, name: String, key: String) =
        sp(c).edit().putString("wake", wake).putString("name", name).putString("key", key).apply()

    fun setAlwaysListen(c: Context, on: Boolean) = sp(c).edit().putBoolean("listen", on).apply()
}

object Text {
    private val marks = Regex("\\p{M}+")
    private val punct = Regex("[¿?¡!.,;:\"'«»()]")
    private val spaces = Regex("\\s+")

    /** "¿Qué HORA es?" -> "que hora es" */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "").replace(punct, " ").replace(spaces, " ").trim()

    /** Español del teléfono si lo tiene, si no español de España. */
    fun spanish(): Locale {
        val d = Locale.getDefault()
        return if (d.language == "es") d else Locale("es", "ES")
    }
}

/**
 * Detección de la palabra de activación sobre el texto que da el reconocedor.
 * "Alay" no es una palabra del diccionario, así que además de las palabras configuradas
 * aceptamos cómo suele transcribirse: "oye a la", "oye allá", "oye alí"…
 */
object Wake {
    private val triggers = setOf("oye", "oie", "oy", "hey", "ey", "ei", "ok", "okay", "okey", "hola")
    private val alay = Regex("^h?al{1,2}[aei][iy]?$")

    private fun regexFor(word: String): Regex {
        val chars = Text.normalize(word).replace(" ", "")
        return Regex("\\b" + chars.map { Regex.escape(it.toString()) }.joinToString("\\s*") + "\\b")
    }

    private fun custom(ctx: Context): List<Regex> =
        Prefs.wake(ctx).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .sortedByDescending { it.length }.map { regexFor(it) }

    /** Devuelve el texto que va después de la palabra de activación, o null si no está. */
    fun find(ctx: Context, normalized: String): String? {
        for (re in custom(ctx)) {
            re.find(normalized)?.let { return normalized.substring(it.range.last + 1).trim() }
        }
        val tokens = normalized.split(" ")
        for (i in tokens.indices) {
            if (tokens[i] !in triggers) continue
            for (n in 1..3) {
                val end = i + 1 + n
                if (end > tokens.size) break
                val joined = tokens.subList(i + 1, end).joinToString("")
                if (joined.length <= 6 && alay.matches(joined)) {
                    return tokens.drop(end).joinToString(" ")
                }
            }
        }
        return null
    }

    fun matches(ctx: Context, normalized: String) = find(ctx, normalized) != null

    /** Quita la palabra de activación si el usuario la repitió al dar la orden. */
    fun strip(ctx: Context, normalized: String) = find(ctx, normalized) ?: normalized
}
