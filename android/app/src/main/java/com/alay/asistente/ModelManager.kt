package com.alay.asistente

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Descarga el modelo de voz en español de Vosk (unos 40 MB, una sola vez).
 * Con él la palabra de activación se detecta en el propio teléfono, sin internet.
 */
object ModelManager {
    private const val URL_ES = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-es")
    fun isReady(ctx: Context) = File(dir(ctx), ".ok").exists()

    /** Bloqueante. [progress] recibe 0..100. */
    fun download(ctx: Context, progress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, "model.zip")
        val conn = URL(URL_ES).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = (done * 90 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }

        val tmp = File(ctx.filesDir, "vosk-tmp").apply { deleteRecursively(); mkdirs() }
        val root = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                // Quitamos la carpeta superior del zip ("vosk-model-small-es-0.42/").
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(tmp, rel)
                if (!f.canonicalPath.startsWith(root)) throw IOException("Ruta no válida en el zip")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
            }
        }
        zip.delete()
        progress(100)
        val dest = dir(ctx)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("No se pudo guardar el modelo")
        File(dest, ".ok").writeText("ok")
    }
}
