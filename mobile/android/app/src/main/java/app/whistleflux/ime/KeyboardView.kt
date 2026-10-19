package app.whistleflux.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.whistleflux.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The whole keyboard in one view, laid out like Gboard: a suggestion strip with a mic key on
 * the right, and four rows of keys. It draws everything itself and turns touches into
 * [Listener] calls; the service ([WhistlefluxKeyboard]) does the typing.
 *
 * Touch behavior:
 * - tap a key; several fingers at once commit in order (fast two-thumb typing),
 * - long-press a top-row letter for its digit,
 * - swipe across letters to type a word (the swipe is drawn as a trail),
 * - slide or long-press the space bar: the whole keyboard becomes a trackpad that moves
 *   the cursor, like on iPhone,
 * - hold delete to keep deleting.
 * In voice mode the mic key grows into the voice pill: tapping the pill finishes the
 * dictation, tapping anywhere else cancels it.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private var theme: KeyboardTheme) : View(context) {
    interface Listener {
        /** A tap on any key except delete (which repeats through [onDelete]). */
        fun onKey(key: Key)
        fun onLongPressText(text: String)
        fun onDelete()
        fun onSwipe(xs: FloatArray, ys: FloatArray)
        fun onSuggestion(index: Int)
        fun onMic()
        fun onPillTap()
        fun onVoiceCancel()
        /** Trackpad movement: one step left/right (dx) or up/down (dy). */
        fun onCursor(dx: Int, dy: Int)
        fun onTrackpadStart()
    }

    enum class Shift { OFF, ON, LOCKED }

    var listener: Listener? = null

    var layout: Layout = Layouts.letters()
        set(value) {
            field = value
            placeKeys()
            invalidate()
        }

    var shift = Shift.OFF
        set(value) {
            field = value
            invalidate()
        }

    var enterIcon: Int = R.drawable.ic_enter
        set(value) {
            field = value
            invalidate()
        }

    /** Swipe typing on the letter keys; off in password fields. */
    var swipeEnabled = true

    /** Up to three words for the strip; [message] replaces them while set. */
    var suggestions: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var message: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Shown above the pill in voice mode ("Listening…"). */
    var voiceStatus: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var pill = PillRenderer(theme)
    val inVoiceMode get() = voiceTarget == 1f

    fun setPillMode(mode: PillRenderer.Mode) {
        pill.mode = mode
        invalidate()
    }

    fun pushLevel(level: Float) = pill.pushLevel(level)

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        pill = PillRenderer(theme)
        icons.clear()
        invalidate()
    }

    // --- sizes ---------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val stripHeight = dp(48f)
    private val rowHeight = dp(54f)
    private val sidePadding = dp(4f)
    private val gapX = dp(3f)
    private val gapY = dp(4f)
    private val keyRadius = dp(8f)
    private val bottomPadding = dp(6f)
    private val micSize = dp(40f)
    private val micRadius = dp(12f)
    private val pillHeight = dp(64f)
    private var navInset = 0

    private val keysTop get() = stripHeight
    private val keysBottom get() = stripHeight + 4 * rowHeight
    private val micRect = RectF()
    private val pillTarget = RectF()

    init {
        isHapticFeedbackEnabled = true
        contentDescription = context.getString(R.string.kb_description)
        // Keep the keys clear of the navigation bar. With gesture navigation the system
        // draws its "hide keyboard" and "switch keyboard" buttons inside the keyboard's
        // window, in a bar of navigation_bar_frame_height; some phones report no navigation
        // bar inset at all for keyboards, which put the bottom row under those buttons.
        val imeButtonBar = if (gestureNavigation()) systemDimen("navigation_bar_frame_height") else 0
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
            val bottom = maxOf(nav, tappable, imeButtonBar)
            if (bottom != navInset) {
                navInset = bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (keysBottom + bottomPadding).toInt() + navInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeKeys()
    }

    private fun placeKeys() {
        if (width == 0) return
        layout.place(sidePadding, keysTop, width - sidePadding, keysBottom)
        val cy = stripHeight / 2
        micRect.set(width - dp(8f) - micSize, cy - micSize / 2, width - dp(8f), cy + micSize / 2)
        val pillWidth = min(dp(240f), width - dp(32f))
        val pillCy = (keysTop + keysBottom) / 2 - dp(10f)
        pillTarget.set((width - pillWidth) / 2, pillCy - pillHeight / 2, (width + pillWidth) / 2, pillCy + pillHeight / 2)
    }

    // --- voice mode ------------------------------------------------------------------------

    private var voiceProgress = 0f // 0 = keyboard, 1 = pill
    private var voiceTarget = 0f
    private var voiceAnimator: ValueAnimator? = null

    /** Grows the mic key into the pill (the keys fade out). */
    fun enterVoice() {
        pill.reset()
        pill.mode = PillRenderer.Mode.RECORDING
        animateVoice(1f)
    }

    /** Shrinks the pill back into the mic key. */
    fun exitVoice() {
        if (voiceTarget == 0f && voiceProgress == 0f) return
        animateVoice(0f)
    }

    private fun animateVoice(target: Float) {
        voiceTarget = target
        cancelTouches()
        voiceAnimator?.cancel()
        voiceAnimator = ValueAnimator.ofFloat(voiceProgress, target).apply {
            duration = (280 * abs(target - voiceProgress)).toLong().coerceAtLeast(1)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // Material "emphasized"
            addUpdateListener {
                voiceProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // --- drawing ---------------------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private val icons = HashMap<Int, Drawable>()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun icon(res: Int): Drawable = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val p = voiceProgress
        val eased = p

        // Keys fade out as the pill grows.
        if (p < 1f) {
            val alpha = ((1 - p) * 255).toInt()
            drawStrip(canvas, alpha)
            drawKeys(canvas, alpha)
            if (p == 0f) {
                drawPreview(canvas)
                drawTrail(canvas)
            }
        }

        // The mic key, growing into the pill.
        rect.set(
            lerp(micRect.left, pillTarget.left, eased), lerp(micRect.top, pillTarget.top, eased),
            lerp(micRect.right, pillTarget.right, eased), lerp(micRect.bottom, pillTarget.bottom, eased),
        )
        val radius = lerp(micRadius, rect.height() / 2, eased)
        fill.color = ColorUtils.blendARGB(if (micPressed) theme.pressedOver(theme.micKey) else theme.micKey, theme.pill, eased)
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (p < 0.5f) {
            drawIcon(canvas, R.drawable.ic_mic, rect.centerX(), rect.centerY(), dp(22f), theme.onMicKey, ((1 - 2 * p) * 255).toInt())
        } else {
            pill.drawContent(canvas, rect, ((2 * p - 1) * 255).toInt())
        }

        if (p > 0f) {
            val alpha = (p * 255).toInt()
            text.typeface = medium
            text.textSize = sp(16f)
            text.color = theme.label
            text.alpha = alpha
            canvas.drawText(ellipsize(voiceStatus, width - dp(32f)), width / 2f, centerTextY(stripHeight / 2 + dp(8f)), text)
            text.typeface = regular
            text.textSize = sp(13f)
            text.color = theme.muted
            text.alpha = alpha
            canvas.drawText(context.getString(R.string.kb_cancel_hint), width / 2f, centerTextY(pillTarget.bottom + dp(28f)), text)
        }
        if (p > 0f && p < 1f || inVoiceMode) postInvalidateOnAnimation()
    }

    private fun drawStrip(canvas: Canvas, alpha: Int) {
        val right = micRect.left - dp(8f)
        val msg = message
        text.alpha = alpha
        if (trackpad) {
            text.typeface = regular
            text.textSize = sp(15f)
            text.color = theme.muted
            text.alpha = alpha
            canvas.drawText(context.getString(R.string.kb_trackpad), right / 2, centerTextY(stripHeight / 2), text)
            return
        }
        if (msg != null) {
            text.typeface = regular
            text.textSize = sp(15f)
            text.color = theme.muted
            text.alpha = alpha
            canvas.drawText(ellipsize(msg, right - dp(16f)), right / 2, centerTextY(stripHeight / 2), text)
            return
        }
        if (suggestions.isEmpty()) return
        val cell = right / 3
        for (i in 0 until 3) {
            val word = suggestions.getOrNull(i) ?: continue
            if (stripPressed == i) {
                fill.color = theme.pressedOver(theme.background)
                fill.alpha = alpha
                rect.set(i * cell + dp(2f), dp(6f), (i + 1) * cell - dp(2f), stripHeight - dp(6f))
                canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            }
            text.typeface = if (i == 1) medium else regular
            text.textSize = sp(16f)
            text.color = theme.label
            text.alpha = alpha
            canvas.drawText(ellipsize(word, cell - dp(12f)), i * cell + cell / 2, centerTextY(stripHeight / 2), text)
            if (i < 2 && suggestions.getOrNull(i + 1) != null) {
                fill.color = theme.divider
                fill.alpha = alpha
                canvas.drawRect((i + 1) * cell - dp(0.5f), dp(14f), (i + 1) * cell + dp(0.5f), stripHeight - dp(14f), fill)
            }
        }
    }

    private fun drawKeys(canvas: Canvas, alpha: Int) {
        val pressedKeys = pointers.values.mapNotNull { if (it.mode == Mode.TAP) it.key else null }.toSet()
        for (key in layout.keys) {
            rect.set(key.left + gapX, key.top + gapY, key.right - gapX, key.bottom - gapY)
            val base = when {
                key.type == KeyType.ENTER -> theme.enterKey
                key.type == KeyType.SHIFT && shift != Shift.OFF -> theme.micKey
                key.isFunction -> theme.functionKey
                else -> theme.key
            }
            fill.color = if (key in pressedKeys || (key.type == KeyType.SPACE && trackpad)) theme.pressedOver(base) else base
            fill.alpha = alpha
            canvas.drawRoundRect(rect, keyRadius, keyRadius, fill)
            if (trackpad) continue // blank keys while the keyboard is a trackpad

            val labelColor = when {
                key.type == KeyType.ENTER -> theme.onEnterKey
                key.type == KeyType.SHIFT && shift != Shift.OFF -> theme.onMicKey
                key.isFunction -> theme.functionLabel
                else -> theme.label
            }
            when (key.type) {
                KeyType.CHAR -> {
                    text.typeface = regular
                    text.textSize = sp(if (key.isLetter) 22f else 20f)
                    text.color = labelColor
                    text.alpha = alpha
                    // With a digit in the corner, the letter sits a little lower to make room.
                    val shift = if (key.hint != null) dp(3f) else 0f
                    canvas.drawText(label(key), rect.centerX(), centerTextY(rect.centerY() + shift), text)
                    key.hint?.let {
                        text.textSize = sp(10f)
                        text.color = theme.muted
                        text.alpha = alpha
                        canvas.drawText(it, rect.right - dp(7f), rect.top + dp(11f), text)
                    }
                }
                KeyType.SHIFT -> drawIcon(
                    canvas,
                    when (shift) {
                        Shift.OFF -> R.drawable.ic_shift
                        Shift.ON -> R.drawable.ic_shift_on
                        Shift.LOCKED -> R.drawable.ic_caps_lock
                    },
                    rect.centerX(), rect.centerY(), dp(22f), labelColor, alpha,
                )
                KeyType.DELETE -> drawIcon(canvas, R.drawable.ic_backspace, rect.centerX(), rect.centerY(), dp(22f), labelColor, alpha)
                KeyType.ENTER -> drawIcon(canvas, enterIcon, rect.centerX(), rect.centerY(), dp(22f), labelColor, alpha)
                KeyType.SPACE -> Unit
                KeyType.SYMBOLS, KeyType.LETTERS, KeyType.MORE_SYMBOLS -> {
                    text.typeface = medium
                    text.textSize = sp(14f)
                    text.color = labelColor
                    text.alpha = alpha
                    canvas.drawText(key.text, rect.centerX(), centerTextY(rect.centerY()), text)
                }
            }
        }
    }

    /** Gboard's key preview: the pressed character, bigger, in a bubble above the key. */
    private fun drawPreview(canvas: Canvas) {
        if (trackpad) return
        val pointer = pointers.values.lastOrNull { it.mode == Mode.TAP && it.key?.type == KeyType.CHAR } ?: return
        val key = pointer.key ?: return
        val w = (key.right - key.left) * 1.15f
        val h = rowHeight * 1.05f
        val left = (key.centerX - w / 2).coerceIn(dp(2f), width - w - dp(2f))
        rect.set(left, key.top + gapY - h - dp(4f), left + w, key.top + gapY - dp(4f))
        fill.color = theme.preview
        canvas.drawRoundRect(rect, keyRadius * 1.5f, keyRadius * 1.5f, fill)
        text.typeface = regular
        text.textSize = sp(30f)
        text.color = theme.label
        canvas.drawText(label(key), rect.centerX(), centerTextY(rect.centerY()), text)
    }

    private fun drawTrail(canvas: Canvas) {
        val pointer = pointers.values.firstOrNull { it.mode == Mode.SWIPE } ?: return
        if (pointer.xs.size < 2) return
        path.reset()
        path.moveTo(pointer.xs[0], pointer.ys[0])
        for (i in 1 until pointer.xs.size) path.lineTo(pointer.xs[i], pointer.ys[i])
        trail.color = theme.trail
        trail.alpha = 200
        trail.strokeWidth = dp(5f)
        canvas.drawPath(path, trail)
    }

    private fun label(key: Key) = if (key.isLetter && shift != Shift.OFF) key.text.uppercase() else key.text

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Int) {
        val d = icon(res)
        d.setTint(color)
        d.alpha = alpha
        val half = (size / 2).toInt()
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
    }

    private fun centerTextY(cy: Float) = cy - (text.descent() + text.ascent()) / 2

    private fun ellipsize(s: String, width: Float) = TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // --- touch -----------------------------------------------------------------------------

    private enum class Mode { TAP, SWIPE, TRACKPAD, STRIP, DONE }

    private inner class Pointer(val id: Int, var key: Key?, val downX: Float, val downY: Float, var mode: Mode) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var anchorX = downX // trackpad: where the last cursor step happened
        var anchorY = downY
        var longPress: Runnable? = null

        /** The key still types when the finger lifts (false after a long-press or rollover). */
        var pending = true
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val handler = Handler(Looper.getMainLooper())
    private var trackpad = false
    private var micPressed = false
    private var stripPressed = -1
    private var deleteRepeat: Runnable? = null
    private var voiceDownInPill = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (voiceTarget == 1f || voiceProgress > 0f) return voiceTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        invalidate()
        return true
    }

    private fun voiceTouch(e: MotionEvent): Boolean {
        if (voiceTarget != 1f) return true // shrinking back: ignore
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> voiceDownInPill = pillTarget.contains(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val inPill = pillTarget.contains(e.x, e.y)
                if (voiceDownInPill && inPill) {
                    haptic()
                    listener?.onPillTap()
                } else if (!voiceDownInPill && !inPill) {
                    haptic()
                    listener?.onVoiceCancel()
                }
            }
        }
        return true
    }

    private fun down(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        val x = e.getX(index)
        val y = e.getY(index)
        haptic()
        if (y < stripHeight) {
            pointers[id] = Pointer(id, null, x, y, Mode.STRIP)
            micPressed = micRect.contains(x, y) || (x > micRect.left - dp(8f))
            stripPressed = if (micPressed) -1 else stripCell(x)
            return
        }
        // Another finger lands: type what the earlier fingers are on now, in order.
        for (other in pointers.values) {
            val otherKey = other.key
            if (other.mode == Mode.TAP && other.pending && otherKey != null && otherKey.type != KeyType.DELETE) {
                cancelLongPress(other)
                other.mode = Mode.DONE
                listener?.onKey(otherKey)
            }
        }
        val key = layout.keyAt(x, y)
        val pointer = Pointer(id, key, x, y, Mode.TAP)
        pointer.xs += x
        pointer.ys += y
        pointers[id] = pointer
        when (key?.type) {
            KeyType.DELETE -> startDeleteRepeat()
            KeyType.SPACE -> scheduleLongPress(pointer, TRACKPAD_DELAY) { startTrackpad(pointer) }
            KeyType.CHAR -> key.hint?.let { hint ->
                scheduleLongPress(pointer, LONG_PRESS_DELAY) {
                    pointer.mode = Mode.DONE
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener?.onLongPressText(hint)
                    invalidate()
                }
            }
            else -> Unit
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val pointer = pointers[id] ?: return
        when (pointer.mode) {
            Mode.TAP -> {
                val key = pointer.key ?: return
                val moved = hypot(x - pointer.downX, y - pointer.downY)
                if (key.type == KeyType.SPACE) {
                    if (abs(x - pointer.downX) > dp(10f)) startTrackpad(pointer)
                } else if (key.isLetter && swipeEnabled && pointers.size == 1 && moved > layout.unitWidth() * 0.5f) {
                    cancelLongPress(pointer)
                    pointer.mode = Mode.SWIPE
                    pointer.xs += x
                    pointer.ys += y
                } else if (key.type == KeyType.CHAR && !key.contains(x, y)) {
                    // Sliding onto another key types that one instead.
                    val now = layout.keyAt(x, y)
                    if (now != null && now != key && now.type == KeyType.CHAR) {
                        cancelLongPress(pointer)
                        pointer.key = now
                    }
                }
            }
            Mode.SWIPE -> {
                pointer.xs += x
                pointer.ys += y
            }
            Mode.TRACKPAD -> {
                val stepX = dp(9f)
                val stepY = dp(28f)
                while (x - pointer.anchorX >= stepX) { listener?.onCursor(1, 0); pointer.anchorX += stepX }
                while (pointer.anchorX - x >= stepX) { listener?.onCursor(-1, 0); pointer.anchorX -= stepX }
                while (y - pointer.anchorY >= stepY) { listener?.onCursor(0, 1); pointer.anchorY += stepY }
                while (pointer.anchorY - y >= stepY) { listener?.onCursor(0, -1); pointer.anchorY -= stepY }
            }
            Mode.STRIP, Mode.DONE -> Unit
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val pointer = pointers.remove(id) ?: return
        cancelLongPress(pointer)
        when (pointer.mode) {
            Mode.TAP -> {
                val key = pointer.key
                if (key?.type == KeyType.DELETE) stopDeleteRepeat()
                else if (key != null && pointer.pending) listener?.onKey(key)
            }
            Mode.SWIPE -> listener?.onSwipe(pointer.xs.toFloatArray(), pointer.ys.toFloatArray())
            Mode.TRACKPAD -> trackpad = pointers.values.any { it.mode == Mode.TRACKPAD }
            Mode.STRIP -> {
                if (micPressed && (micRect.contains(x, y) || x > micRect.left - dp(8f)) && y < stripHeight) {
                    listener?.onMic()
                } else if (stripPressed >= 0 && stripCell(x) == stripPressed && y < stripHeight) {
                    listener?.onSuggestion(stripPressed)
                }
                micPressed = false
                stripPressed = -1
            }
            Mode.DONE -> Unit
        }
    }

    private fun stripCell(x: Float): Int {
        if (message != null || suggestions.isEmpty()) return -1
        val cell = (micRect.left - dp(8f)) / 3
        val i = (x / cell).toInt()
        return if (i in 0..2 && suggestions.getOrNull(i) != null) i else -1
    }

    private fun startTrackpad(pointer: Pointer) {
        cancelLongPress(pointer)
        if (pointer.mode == Mode.TRACKPAD) return
        pointer.mode = Mode.TRACKPAD
        trackpad = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onTrackpadStart()
        invalidate()
    }

    private fun scheduleLongPress(pointer: Pointer, delay: Long, action: () -> Unit) {
        val r = Runnable {
            pointer.longPress = null
            pointer.pending = false
            action()
        }
        pointer.longPress = r
        handler.postDelayed(r, delay)
    }

    private fun cancelLongPress(pointer: Pointer) {
        pointer.longPress?.let { handler.removeCallbacks(it) }
        pointer.longPress = null
    }

    private fun startDeleteRepeat() {
        listener?.onDelete()
        val r = object : Runnable {
            override fun run() {
                listener?.onDelete()
                handler.postDelayed(this, DELETE_REPEAT)
            }
        }
        deleteRepeat = r
        handler.postDelayed(r, LONG_PRESS_DELAY)
    }

    private fun stopDeleteRepeat() {
        deleteRepeat?.let { handler.removeCallbacks(it) }
        deleteRepeat = null
    }

    fun cancelTouches() {
        pointers.values.forEach { cancelLongPress(it) }
        pointers.clear()
        stopDeleteRepeat()
        trackpad = false
        micPressed = false
        stripPressed = -1
        invalidate()
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        voiceAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // --- system values ---------------------------------------------------------------------

    /** Whether the phone uses gesture navigation (2) rather than 2- or 3-button navigation. */
    private fun gestureNavigation(): Boolean {
        @SuppressLint("DiscouragedApi")
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        return id != 0 && resources.getInteger(id) == 2
    }

    /** A dimension from the Android system's own resources, in pixels; 0 if missing. */
    private fun systemDimen(name: String): Int {
        @SuppressLint("DiscouragedApi")
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val LONG_PRESS_DELAY = 400L
        const val TRACKPAD_DELAY = 350L
        const val DELETE_REPEAT = 50L
    }
}
