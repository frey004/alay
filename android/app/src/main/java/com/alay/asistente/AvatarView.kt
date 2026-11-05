package com.alay.asistente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Avatar animado dibujado a mano: flota, parpadea, brilla al escuchar,
 * mueve la cabeza al pensar y abre la boca al ritmo de la voz.
 * Coordenadas en un lienzo de 200 x 220 que se escala al tamaño de la vista.
 */
class AvatarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state = State.IDLE
    @Volatile var speaking = false

    /** Llamar en cada palabra que pronuncia la voz: da un "golpe" a la boca. */
    fun pulse() { wordPulse = 1f }

    @Volatile private var wordPulse = 0f
    private var mouth = 0f
    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private val oval = RectF()

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(100f, 110f, 100f,
            intArrayOf(0x737C5CFF, 0x737C5CFF, 0x007C5CFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(100f, 85f, 95f,
            Color.parseColor("#9D85FF"), Color.parseColor("#5B3DF5"), Shader.TileMode.CLAMP)
    }
    private val antennaPaint = paint(Color.parseColor("#C9BCFF"))
    private val facePaint = paint(Color.parseColor("#141A33"))
    private val eyePaint = paint(Color.parseColor("#7EF0FF"))
    private val cheekPaint = paint(Color.parseColor("#59FF7EB6"))
    private val mouthPaint = paint(Color.parseColor("#7EF0FF"))

    private fun Canvas.ellipse(cx: Float, cy: Float, rx: Float, ry: Float, p: Paint) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry); drawOval(oval, p)
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis().toFloat()
        val s = min(width / 200f, height / 220f)
        c.save()
        c.translate((width - 200 * s) / 2, (height - 220 * s) / 2)
        c.scale(s, s)

        // Aura: late cuando escucha.
        val auraScale = if (state == State.LISTENING) 0.92f + 0.16f * (0.5f + 0.5f * sin(t / 190f)) else 1f
        auraPaint.alpha = if (state == State.LISTENING) 255 else 110
        c.save(); c.scale(auraScale, auraScale, 100f, 110f)
        c.drawCircle(100f, 110f, 100f, auraPaint)
        c.restore()

        // Cabeza: flota; al pensar se balancea.
        c.save()
        c.translate(0f, -3f - 3f * sin(t / 640f))
        if (state == State.THINKING) c.rotate(4f * sin(t / 160f), 100f, 180f)

        c.drawCircle(100f, 22f, 7f, antennaPaint)
        c.drawRoundRect(97f, 26f, 103f, 46f, 3f, 3f, antennaPaint)
        c.ellipse(100f, 115f, 72f, 75f, headPaint)
        c.ellipse(100f, 120f, 54f, 48f, facePaint)

        // Ojos con parpadeo.
        var eyeRy = 11f
        val now = SystemClock.uptimeMillis()
        if (now > nextBlink) {
            val k = (now - nextBlink) / 140f
            if (k < 1f) eyeRy = 11f * abs(1f - 2f * k) + 1f
            else nextBlink = now + 2000 + (Math.random() * 3500).toLong()
        }
        if (state == State.THINKING) eyeRy = min(eyeRy, 6f)
        c.ellipse(80f, 108f, 9f, eyeRy, eyePaint)
        c.ellipse(120f, 108f, 9f, eyeRy, eyePaint)

        c.ellipse(66f, 132f, 8f, 4f, cheekPaint)
        c.ellipse(134f, 132f, 8f, 4f, cheekPaint)

        // Boca: lip-sync aproximado (sílabas + golpe en cada palabra).
        val target = when {
            speaking -> {
                val syll = abs(sin(t / 70f)) * 0.6f + abs(sin(t / 113f)) * 0.4f
                val v = min(1f, 0.15f + syll * 0.65f + wordPulse * 0.4f)
                wordPulse *= 0.9f
                v
            }
            state == State.LISTENING -> 0.08f
            else -> 0f
        }
        mouth += (target - mouth) * 0.35f
        c.ellipse(100f, 142f, 14f - mouth * 3f, 2f + mouth * 14f, mouthPaint)

        c.restore()
        c.restore()
        postInvalidateOnAnimation()
    }
}
