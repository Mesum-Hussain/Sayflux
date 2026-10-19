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
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.whistleflux.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The whole keyboard in one view: a suggestion strip (or toolbar), and the keys (the mic
 * view, QWERTY, symbols, or a number pad); while searching emoji, a search bar on top. It draws everything itself and turns touches into [Listener] calls; the service
 * ([WhistlefluxKeyboard]) does the typing.
 *
 * Touch behavior:
 * - tap a key; several fingers at once commit in order (fast two-thumb typing),
 * - long-press a key for its digit, or for a row of symbols to slide across and pick from,
 * - swipe across letter keys to type a word (the keys you pass light up, and the swipe is
 *   drawn as a trail),
 * - slide or long-press the space bar: the whole keyboard becomes a trackpad that moves the
 *   cursor, like on iPhone,
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
        fun onPillTap()
        fun onVoiceCancel()
        /** Trackpad movement: one step left/right (dx) or up/down (dy). */
        fun onCursor(dx: Int, dy: Int)
        fun onTrackpadStart()
        fun onTool(tool: Tool)
        fun onSearchResult(index: Int)
        fun onSearchClose()
    }

    enum class Shift { OFF, ON, LOCKED }

    /** Buttons in the strip's toolbar, shown when there are no suggestions. */
    enum class Tool(val icon: Int) {
        EMOJI(R.drawable.ic_emoji),
        CLIPBOARD(R.drawable.ic_clipboard),
        NUMPAD(R.drawable.ic_dialpad),
        /** Switches between the mic view and QWERTY. */
        KEYBOARD(R.drawable.ic_keyboard),
        UNDO(R.drawable.ic_undo),
        REDO(R.drawable.ic_redo),
    }

    /** The open panel's toolbar button is highlighted; its keys area is left to the panel. */
    var openTool: Tool? = null
        set(value) {
            field = value
            cancelTouches()
            invalidate()
        }
    private val panelOpen get() = openTool == Tool.EMOJI || openTool == Tool.CLIPBOARD

    /** Top of the keys area and its height (with the bottom padding), for panels laid over it. */
    val keysAreaTop get() = keysTop.toInt()
    val keysAreaHeight get() = (layout.heightInRows * rowHeight + bottomPadding).toInt()

    var listener: Listener? = null

    var layout: Layout = Layouts.voice()
        set(value) {
            val resized = value.rowHeightDp != field.rowHeightDp || value.heightInRows != field.heightInRows
            field = value
            cancelTouches()
            placeKeys()
            if (resized) requestLayout()
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

    /** The emoji search query; null when not searching. While set, a search bar sits on top. */
    var searchQuery: String? = null
        set(value) {
            val resized = (value == null) != (field == null)
            field = value
            if (resized) {
                cancelTouches()
                placeKeys()
                requestLayout()
            }
            invalidate()
        }

    /** Emoji matching [searchQuery], shown in the strip. */
    var searchResults: List<String> = emptyList()
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
    private val rowHeight get() = dp(layout.rowHeightDp)
    private val sidePadding = dp(4f)
    private val gapX = dp(3f)
    private val gapY = dp(4f)
    private val keyRadius = dp(8f)
    private val bottomPadding = dp(6f)
    private val pillHeight = dp(64f)
    private val searchBarHeight = dp(52f)
    private var navInset = 0

    private val stripTop get() = if (searchQuery != null) searchBarHeight else 0f
    private val keysTop get() = stripTop + stripHeight
    private val keysBottom get() = keysTop + layout.heightInRows * rowHeight
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
        // The pill grows out of the mic key (or the middle of the bottom row if there's none).
        val mic = layout.keys.firstOrNull { it.type == KeyType.MIC }
        if (mic != null && layout.voice) {
            bigMic(mic)
            micRect.set(rect)
        } else if (mic != null) {
            micRect.set(mic.left + gapX, mic.top + gapY, mic.right - gapX, mic.bottom - gapY)
        } else {
            micRect.set(width / 2f - dp(20f), keysBottom - rowHeight, width / 2f + dp(20f), keysBottom - gapY)
        }
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

        // Keys fade out as the pill grows.
        if (p < 1f) {
            val alpha = ((1 - p) * 255).toInt()
            if (searchQuery != null) drawSearchBar(canvas)
            canvas.withTranslation(0f, stripTop) { drawStrip(this, alpha) }
            if (!panelOpen) drawKeys(canvas, alpha)
            if (p == 0f && !panelOpen) {
                drawPreview(canvas)
                drawTrail(canvas)
                drawPopup(canvas)
            }
        }

        // The mic key, growing into the pill (drawn with the keys while at rest).
        if (p > 0f) {
            rect.set(
                lerp(micRect.left, pillTarget.left, p), lerp(micRect.top, pillTarget.top, p),
                lerp(micRect.right, pillTarget.right, p), lerp(micRect.bottom, pillTarget.bottom, p),
            )
            val radius = lerp(keyRadius, rect.height() / 2, p)
            fill.color = ColorUtils.blendARGB(theme.micKey, theme.pill, p)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (p < 0.5f) {
                drawIcon(canvas, R.drawable.ic_mic, rect.centerX(), rect.centerY(), dp(24f), theme.onMicKey, ((1 - 2 * p) * 255).toInt())
            } else {
                pill.drawContent(canvas, rect, ((2 * p - 1) * 255).toInt())
            }

            val alpha = (p * 255).toInt()
            text.typeface = medium
            text.textSize = sp(16f)
            text.color = theme.label
            text.alpha = alpha
            canvas.drawText(ellipsize(voiceStatus, width - dp(32f)), width / 2f, centerTextY(stripTop + stripHeight / 2 + dp(8f)), text)
            text.typeface = regular
            text.textSize = sp(13f)
            text.color = theme.muted
            text.alpha = alpha
            canvas.drawText(context.getString(R.string.kb_cancel_hint), width / 2f, centerTextY(pillTarget.bottom + dp(28f)), text)
        }
        if (p > 0f && p < 1f || inVoiceMode) postInvalidateOnAnimation()
    }

    /** Emoji search: a field with the query (typed on the keys below) and a close button. */
    private fun drawSearchBar(canvas: Canvas) {
        rect.set(dp(8f), dp(6f), width - dp(56f), searchBarHeight - dp(6f))
        fill.color = theme.key
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        drawIcon(canvas, R.drawable.ic_search, rect.left + dp(22f), rect.centerY(), dp(20f), theme.muted, 255)
        val query = searchQuery.orEmpty()
        text.typeface = regular
        text.textSize = sp(16f)
        text.textAlign = Paint.Align.LEFT
        text.color = if (query.isEmpty()) theme.muted else theme.label
        val shown = if (query.isEmpty()) context.getString(R.string.kb_search_emoji) else query
        val x = rect.left + dp(42f)
        canvas.drawText(ellipsize(shown, rect.right - x - dp(16f)), x, centerTextY(rect.centerY()), text)
        // A cursor after the query, so it's clear the keys type here.
        val cursorX = x + if (query.isEmpty()) 0f else text.measureText(query) + dp(2f)
        fill.color = theme.accent
        canvas.drawRect(cursorX, rect.centerY() - dp(10f), cursorX + dp(2f), rect.centerY() + dp(10f), fill)
        text.textAlign = Paint.Align.CENTER
        if (searchClosePressed) {
            fill.color = theme.pressedOver(theme.background)
            canvas.drawCircle(width - dp(28f), searchBarHeight / 2, dp(20f), fill)
        }
        drawIcon(canvas, R.drawable.ic_close, width - dp(28f), searchBarHeight / 2, dp(22f), theme.label, 255)
    }

    private fun drawSearchResults(canvas: Canvas, alpha: Int) {
        if (searchResults.isEmpty()) {
            text.typeface = regular
            text.textSize = sp(15f)
            text.color = theme.muted
            text.alpha = alpha
            canvas.drawText(context.getString(R.string.kb_no_emoji), width / 2f, centerTextY(stripHeight / 2), text)
            return
        }
        text.typeface = regular
        text.textSize = sp(26f)
        text.color = theme.label
        text.alpha = alpha
        for (i in 0 until min(searchResults.size, resultSlots())) {
            val cx = dp(8f) + i * dp(RESULT_CELL_DP) + dp(RESULT_CELL_DP) / 2
            if (i == stripPressed) {
                fill.color = theme.pressedOver(theme.background)
                canvas.drawCircle(cx, stripHeight / 2, dp(21f), fill)
            }
            canvas.drawText(searchResults[i], cx, centerTextY(stripHeight / 2), text)
        }
    }

    private fun resultSlots() = ((width - dp(16f)) / dp(RESULT_CELL_DP)).toInt()

    private fun drawStrip(canvas: Canvas, alpha: Int) {
        if (searchQuery != null) return drawSearchResults(canvas, alpha)
        val right = width - dp(8f)
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
        if (suggestions.isEmpty() || panelOpen) return drawToolbar(canvas, alpha)
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

    private fun toolRect(i: Int) {
        val size = dp(TOOL_SIZE_DP)
        val left = dp(8f) + i * dp(TOOL_CELL_DP)
        rect.set(left, (stripHeight - size) / 2, left + size, (stripHeight + size) / 2)
    }

    private fun drawToolbar(canvas: Canvas, alpha: Int) {
        Tool.entries.forEachIndexed { i, tool ->
            toolRect(i)
            val active = tool == openTool || (tool == Tool.KEYBOARD && qwertyShown) || (tool == Tool.NUMPAD && numpadShown)
            if (active || toolPressed == i) {
                fill.color = if (active) theme.micKey else theme.pressedOver(theme.background)
                fill.alpha = alpha
                canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
            }
            drawIcon(canvas, tool.icon, rect.centerX(), rect.centerY(), dp(19f), if (active) theme.onMicKey else theme.muted, alpha)
        }
    }

    /** QWERTY is showing (the toolbar's keyboard button is lit, and tapping it goes back to the mic). */
    var qwertyShown = false
        set(value) {
            field = value
            invalidate()
        }

    /** The number pad is showing (its toolbar button is lit, and tapping it goes back). */
    var numpadShown = false
        set(value) {
            field = value
            invalidate()
        }

    private fun drawKeys(canvas: Canvas, alpha: Int) {
        val pressedKeys = pointers.values.mapNotNull { if (it.mode == Mode.TAP) it.key else null }.toSet()
        // While swiping, the key under the finger is lit in the accent color and the keys
        // already passed are tinted, so you can see what you're spelling.
        val swipe = pointers.values.firstOrNull { it.mode == Mode.SWIPE }
        for (key in layout.keys) {
            rect.set(key.left + gapX, key.top + gapY, key.right - gapX, key.bottom - gapY)
            val swipeState = when {
                swipe == null -> 0
                key == swipe.current -> 2
                key in swipe.visited -> 1
                else -> 0
            }
            val base = when {
                swipeState == 2 -> theme.enterKey
                swipeState == 1 -> theme.micKey
                key.type == KeyType.ENTER -> theme.enterKey
                key.type == KeyType.MIC -> theme.micKey
                key.type == KeyType.SHIFT && shift != Shift.OFF -> theme.micKey
                key.isFunction -> theme.functionKey
                else -> theme.key
            }
            fill.color = if (key in pressedKeys || (key.type == KeyType.SPACE && trackpad)) theme.pressedOver(base) else base
            fill.alpha = alpha
            if (key.type == KeyType.MIC && layout.voice) {
                drawBigMic(canvas, key, fill.color, alpha)
                continue
            }
            canvas.drawRoundRect(rect, keyRadius, keyRadius, fill)
            if (trackpad) continue // blank keys while the keyboard is a trackpad

            val labelColor = when {
                swipeState == 2 -> theme.onEnterKey
                swipeState == 1 -> theme.onMicKey
                key.type == KeyType.ENTER -> theme.onEnterKey
                key.type == KeyType.MIC -> theme.onMicKey
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
                    drawHint(canvas, key, alpha)
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
                KeyType.SPACE -> drawHint(canvas, key, alpha)
                KeyType.MIC -> drawIcon(canvas, R.drawable.ic_mic, rect.centerX(), rect.centerY(), dp(24f), labelColor, alpha)
                KeyType.TO_QWERTY -> drawIcon(canvas, R.drawable.ic_keyboard, rect.centerX(), rect.centerY(), dp(24f), labelColor, alpha)
                KeyType.SYMBOLS, KeyType.LETTERS_PAGE -> {
                    text.typeface = medium
                    text.textSize = sp(16f)
                    text.color = labelColor
                    text.alpha = alpha
                    canvas.drawText(key.text, rect.centerX(), centerTextY(rect.centerY()), text)
                }
            }
        }
    }

    /** A key's digit (or long-press character), small in its top-right corner. */
    private fun drawHint(canvas: Canvas, key: Key, alpha: Int) {
        val hint = key.hint ?: return
        text.typeface = regular
        text.textSize = sp(10f)
        text.color = theme.muted
        text.alpha = alpha
        canvas.drawText(hint, rect.right - dp(7f), rect.top + dp(11f), text)
    }

    /** The mic view's mic: a big rounded square in the middle of its row (into [rect]). */
    private fun bigMic(key: Key) {
        val size = min(dp(88f), key.bottom - key.top - dp(44f))
        val cy = key.centerY - dp(10f)
        rect.set(key.centerX - size / 2, cy - size / 2, key.centerX + size / 2, cy + size / 2)
    }

    private fun drawBigMic(canvas: Canvas, key: Key, color: Int, alpha: Int) {
        bigMic(key)
        canvas.drawRoundRect(rect, dp(24f), dp(24f), fill.apply { this.color = color; this.alpha = alpha })
        drawIcon(canvas, R.drawable.ic_mic, rect.centerX(), rect.centerY(), dp(36f), theme.onMicKey, alpha)
        text.typeface = regular
        text.textSize = sp(14f)
        text.color = theme.muted
        text.alpha = alpha
        canvas.drawText(context.getString(R.string.kb_tap_to_speak), rect.centerX(), centerTextY(rect.bottom + dp(20f)), text)
    }

    private fun drawPopup(canvas: Canvas) {
        val pop = popup ?: return
        fill.color = theme.preview
        canvas.drawRoundRect(pop.rect, keyRadius * 1.5f, keyRadius * 1.5f, fill)
        pop.items.forEachIndexed { i, item ->
            val left = pop.rect.left + i * pop.cell
            if (i == pop.selected) {
                fill.color = theme.enterKey
                rect.set(left + dp(3f), pop.rect.top + dp(4f), left + pop.cell - dp(3f), pop.rect.bottom - dp(4f))
                canvas.drawRoundRect(rect, keyRadius, keyRadius, fill)
            }
            text.typeface = regular
            text.textSize = sp(22f)
            text.color = if (i == pop.selected) theme.onEnterKey else theme.label
            canvas.drawText(item, left + pop.cell / 2, centerTextY(pop.rect.centerY()), text)
        }
    }

    /** Opens [items] above [key], with the item nearest the finger selected. */
    private fun openPopup(pointer: Pointer, key: Key, items: List<String>) {
        val cell = min(dp(40f), (width - dp(8f)) / items.size)
        val w = cell * items.size
        val left = (key.centerX - w / 2).coerceIn(dp(4f), width - w - dp(4f))
        val bottom = key.top + gapY - dp(4f)
        val pop = Popup(items, RectF(left, bottom - dp(56f), left + w, bottom), cell)
        pop.select(pointer.downX)
        popup = pop
        pointer.mode = Mode.POPUP
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
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

    private enum class Mode { TAP, SWIPE, TRACKPAD, POPUP, STRIP, DONE }

    /** A long-press row of characters to slide across; the one under the finger is picked on release. */
    private class Popup(val items: List<String>, val rect: RectF, val cell: Float) {
        var selected = 0

        fun select(x: Float) {
            selected = ((x - rect.left) / cell).toInt().coerceIn(0, items.lastIndex)
        }
    }

    private var popup: Popup? = null

    private inner class Pointer(val id: Int, var key: Key?, val downX: Float, val downY: Float, var mode: Mode) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var anchorX = downX // trackpad: where the last cursor step happened
        var anchorY = downY
        var longPress: Runnable? = null

        /** The key still types when the finger lifts (false after a long-press or rollover). */
        var pending = true

        /** Swipe: the keys passed so far, and the one under the finger. */
        val visited = LinkedHashSet<Key>()
        var current: Key? = null
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val handler = Handler(Looper.getMainLooper())
    private var trackpad = false
    private var searchClosePressed = false
    private var stripPressed = -1
    private var toolPressed = -1
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
        if (y < stripTop) { // the emoji search bar
            pointers[id] = Pointer(id, null, x, y, Mode.STRIP)
            searchClosePressed = x > width - dp(56f)
            return
        }
        if (y < keysTop) {
            pointers[id] = Pointer(id, null, x, y, Mode.STRIP)
            toolPressed = if (!toolbarShown()) -1 else toolAt(x)
            stripPressed = if (toolPressed >= 0) -1 else stripCell(x)
            return
        }
        if (panelOpen) return
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
        val popupItems = key?.popup
        val hint = key?.hint
        when {
            key == null -> Unit
            key.type == KeyType.DELETE -> startDeleteRepeat()
            popupItems != null -> scheduleLongPress(pointer, LONG_PRESS_DELAY) { openPopup(pointer, key, popupItems) }
            hint != null && (key.type == KeyType.CHAR || key.type == KeyType.SPACE) ->
                scheduleLongPress(pointer, LONG_PRESS_DELAY) {
                    pointer.mode = Mode.DONE
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener?.onLongPressText(hint)
                    invalidate()
                }
            key.type == KeyType.SPACE -> scheduleLongPress(pointer, TRACKPAD_DELAY) { startTrackpad(pointer) }
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
                } else if (key.swipeable && swipeEnabled && pointers.size == 1 && moved > layout.unitWidth() * 0.5f) {
                    cancelLongPress(pointer)
                    pointer.mode = Mode.SWIPE
                    pointer.visited += key
                    trackSwipe(pointer, x, y)
                } else if (key.type == KeyType.CHAR && !key.contains(x, y)) {
                    // Sliding onto another key types that one instead.
                    val now = layout.keyAt(x, y)
                    if (now != null && now != key && now.type == KeyType.CHAR) {
                        cancelLongPress(pointer)
                        pointer.key = now
                    }
                }
            }
            Mode.SWIPE -> trackSwipe(pointer, x, y)
            Mode.POPUP -> popup?.let {
                val before = it.selected
                it.select(x)
                if (it.selected != before) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
            Mode.POPUP -> {
                popup?.let { listener?.onLongPressText(it.items[it.selected]) }
                popup = null
            }
            Mode.STRIP -> {
                val inStrip = y >= stripTop && y < keysTop
                if (searchClosePressed && y < stripTop && x > width - dp(56f)) {
                    listener?.onSearchClose()
                } else if (toolPressed >= 0 && inStrip && toolAt(x) == toolPressed) {
                    listener?.onTool(Tool.entries[toolPressed])
                } else if (stripPressed >= 0 && inStrip && stripCell(x) == stripPressed) {
                    if (searchQuery != null) listener?.onSearchResult(stripPressed) else listener?.onSuggestion(stripPressed)
                }
                searchClosePressed = false
                stripPressed = -1
                toolPressed = -1
            }
            Mode.DONE -> Unit
        }
    }

    private fun toolbarShown() = searchQuery == null && message == null && !trackpad && (suggestions.isEmpty() || panelOpen)

    private fun toolAt(x: Float): Int {
        val i = ((x - dp(8f)) / dp(TOOL_CELL_DP)).toInt()
        return if (x >= dp(8f) && i in Tool.entries.indices) i else -1
    }

    private fun stripCell(x: Float): Int {
        if (searchQuery != null) {
            val i = ((x - dp(8f)) / dp(RESULT_CELL_DP)).toInt()
            return if (x >= dp(8f) && i < min(searchResults.size, resultSlots())) i else -1
        }
        if (message != null || suggestions.isEmpty()) return -1
        val cell = (width - dp(8f)) / 3
        val i = (x / cell).toInt()
        return if (i in 0..2 && suggestions.getOrNull(i) != null) i else -1
    }

    /** Swipe: records the point, and which key it's on for the highlight. */
    private fun trackSwipe(pointer: Pointer, x: Float, y: Float) {
        pointer.xs += x
        pointer.ys += y
        val key = layout.keyAt(x, y)?.takeIf { it.swipeable && it.contains(x, y) }
        if (key != null && key != pointer.current) {
            pointer.current = key
            pointer.visited += key
        }
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
        searchClosePressed = false
        stripPressed = -1
        toolPressed = -1
        popup = null
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
        const val TOOL_SIZE_DP = 38f
        const val TOOL_CELL_DP = 42f
        const val RESULT_CELL_DP = 46f
    }
}
