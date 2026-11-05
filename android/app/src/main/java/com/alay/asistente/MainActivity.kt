package com.alay.asistente

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Pantalla de configuración: permisos, descarga del modelo de voz y ajustes. */
class MainActivity : Activity() {

    private lateinit var btnPerms: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnModel: Button
    private lateinit var btnBattery: Button
    private lateinit var swListen: Switch
    private lateinit var progress: ProgressBar
    private lateinit var summary: TextView
    private var downloading = false
    private var updating = false

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnPerms = findViewById(R.id.btnPerms)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnModel = findViewById(R.id.btnModel)
        btnBattery = findViewById(R.id.btnBattery)
        swListen = findViewById(R.id.swListen)
        progress = findViewById(R.id.progress)
        summary = findViewById(R.id.summary)

        val etWake = findViewById<EditText>(R.id.etWake).apply { setText(Prefs.wake(this@MainActivity)) }
        val etName = findViewById<EditText>(R.id.etName).apply { setText(Prefs.name(this@MainActivity)) }
        val etKey = findViewById<EditText>(R.id.etKey).apply { setText(Prefs.key(this@MainActivity)) }

        btnPerms.setOnClickListener { requestPerms() }
        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnModel.setOnClickListener { downloadModel() }
        btnBattery.setOnClickListener { askBattery() }
        findViewById<Button>(R.id.btnAssistant).setOnClickListener {
            Toast.makeText(this, "Elige Alay en “App de asistente digital”", Toast.LENGTH_LONG).show()
            if (!tryStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)))
                tryStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!hasMic()) { requestPerms(); return@setOnClickListener }
            startActivity(Intent(this, AssistantActivity::class.java))
        }
        swListen.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            if (on && (!hasMic() || !ModelManager.isReady(this))) {
                swListen.isChecked = false
                Toast.makeText(this, "Primero completa los pasos 1 y 3.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            Prefs.setAlwaysListen(this, on)
            if (on) WakeWordService.start(this) else WakeWordService.stop(this)
            refresh()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.save(this, etWake.text.toString().trim(), etName.text.toString().trim(), etKey.text.toString().trim())
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // Volver a arrancar la escucha si estaba activada (p. ej. tras reiniciar el teléfono).
        if (Prefs.alwaysListen(this) && hasMic() && ModelManager.isReady(this) && WakeWordService.instance == null)
            WakeWordService.start(this)
        refresh()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val ok = "✅  "; val todo = "▶️  "
        btnPerms.text = (if (hasMic()) ok else todo) + "1. Permiso de micrófono y notificaciones"
        btnOverlay.text = (if (Settings.canDrawOverlays(this)) ok else todo) +
            "2. Permitir que aparezca sobre otras apps"
        btnModel.text = when {
            downloading -> "⏳  3. Descargando modelo de voz…"
            ModelManager.isReady(this) -> ok + "3. Modelo de voz descargado"
            else -> todo + "3. Descargar modelo de voz (40 MB, una vez)"
        }
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        btnBattery.text = (if (ignoring) ok else todo) + "4. Que Android no la cierre para ahorrar batería"
        updating = true
        swListen.isChecked = Prefs.alwaysListen(this)
        updating = false
        val wake = Prefs.wake(this).split(",").first().trim().replaceFirstChar { it.uppercase() }
        summary.text = if (Prefs.alwaysListen(this) && WakeWordService.instance != null)
            "Estoy escuchando. Di “$wake” desde cualquier app, incluso con la pantalla bloqueada."
        else "Completa los pasos y activa la escucha para llamarme con “$wake”. " +
            "También puedes ponerme como asistente del teléfono."
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasMic()) Toast.makeText(this, "Sin el micrófono no puedo escucharte.", Toast.LENGTH_LONG).show()
        refresh()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        refresh()
        Thread {
            val error = try {
                ModelManager.download(this) { p -> runOnUiThread { progress.progress = p } }
                null
            } catch (e: Exception) { e.message ?: "error" }
            runOnUiThread {
                downloading = false
                progress.visibility = View.GONE
                Toast.makeText(this, if (error == null) "Modelo listo" else "No se pudo descargar: $error",
                    Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }
}
