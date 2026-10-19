package app.whistleflux.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the voice pill, a port of the desktop app's recording indicator
 * (src/whistleflux/overlay.py) with the same bar count and motion:
 * - recording: a pulsing red dot and bars that follow the voice, rippling out from the center,
 * - working: the bars roll in a wave in the accent color.
 */
class PillRenderer(private val theme: KeyboardTheme) {
    enum class Mode { RECORDING, WORKING }

    var mode = Mode.RECORDING
        set(value) {
            if (field != value) started = SystemClock.uptimeMillis()
            field = value
        }

    private val history = FloatArray(BARS / 2 + 1) // newest level first
    private val heights = FloatArray(BARS)
    private var started = SystemClock.uptimeMillis()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.pillDot }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun reset() {
        history.fill(0f)
        heights.fill(0f)
        started = SystemClock.uptimeMillis()
    }

    /** Mic loudness, 0..1. New levels enter at the center and move outward. */
    fun pushLevel(level: Float) {
        if (mode != Mode.RECORDING) return
        System.arraycopy(history, 0, history, 1, history.size - 1)
        history[0] = level.coerceIn(0f, 1f)
    }

    /** Draws the dot and bars inside [rect] (the pill's background is drawn by the caller). */
    fun drawContent(canvas: Canvas, rect: RectF, alpha: Int) {
        val h = rect.height()
        val s = h / DESKTOP_HEIGHT // the desktop pill is 36 px tall
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        val mid = BARS / 2
        for (i in 0 until BARS) {
            val dist = abs(i - mid)
            if (mode == Mode.RECORDING) {
                // Taper toward the edges so the shape reads as a voice wave.
                val target = history[dist] * (1 - 0.45f * dist / mid)
                heights[i] += (target - heights[i]) * 0.45f
            } else {
                val phase = t * 5 - dist * 0.7f
                heights[i] = 0.18f + 0.22f * (0.5f + 0.5f * sin(phase))
            }
        }

        val cy = rect.centerY()
        val dotX = rect.left + h / 2 + 2 * s
        val dotR = 4 * s
        if (mode == Mode.RECORDING) {
            val pulse = 0.5f + 0.5f * sin(t * 4)
            dotPaint.alpha = alpha
            canvas.drawCircle(dotX, cy, (3.2f + 1.2f * pulse) * s, dotPaint)
        }
        val span = (BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP) * s
        val left = dotX + dotR + (rect.right - h / 2 - (dotX + dotR) - span) / 2
        barPaint.color = if (mode == Mode.RECORDING) theme.pillBar else theme.pillWorking
        barPaint.alpha = alpha
        barPaint.strokeWidth = BAR_WIDTH * s
        val usable = (h - 14 * s) / 2
        for (i in 0 until BARS) {
            val x = left + (i * (BAR_WIDTH + BAR_GAP) + BAR_WIDTH / 2) * s
            val half = max(0.5f * s, usable * min(1f, heights[i]))
            canvas.drawLine(x, cy - half, x, cy + half, barPaint)
        }
    }

    companion object {
        // From src/whistleflux/overlay.py.
        private const val BARS = 15
        private const val BAR_WIDTH = 3f
        private const val BAR_GAP = 3f
        private const val DESKTOP_HEIGHT = 36f
    }
}
