package app.whistleflux.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.edit
import kotlin.math.max
import kotlin.math.min

/**
 * Emoji for the panel and search, from Unicode's emoji list and CLDR's English keywords
 * (built into assets/emoji/emoji.txt as "group<TAB>emoji<TAB>name<TAB>keywords" lines, see
 * prepareEmoji in android/app/build.gradle). Only emoji this phone's font can draw are kept.
 */
class EmojiData private constructor(val all: List<Emoji>) {
    class Emoji(val text: String, val group: String, val name: String, keywords: String) {
        /** Every word of the name and keywords, lowercase, for search. */
        val words: List<String> = (name.lowercase().split(' ', ':', '-', ',') + keywords.split(' ')).filter { it.isNotEmpty() }
        val nameWords: Set<String> = name.lowercase().split(' ', ':', '-', ',').filter { it.isNotEmpty() }.toSet()
    }

    val groups: List<Pair<String, List<String>>> = all.groupBy { it.group }.map { (g, list) -> g to list.map { it.text } }

    /**
     * Emoji matching a description: every word typed must start one of the emoji's words
     * ("red he" finds ❤️). Name matches rank above keyword matches; recent emoji first.
     */
    fun search(query: String, recent: List<String>, limit: Int = 40): List<String> {
        val terms = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return recent.take(limit)
        val recentRank = recent.withIndex().associate { it.value to it.index }
        return all.asSequence()
            .filter { e -> terms.all { term -> e.words.any { it.startsWith(term) } } }
            .sortedWith(
                compareBy<Emoji>(
                    { recentRank[it.text] ?: Int.MAX_VALUE },
                    { e -> -terms.count { term -> e.nameWords.any { it == term } } },
                    { e -> -terms.count { term -> e.nameWords.any { it.startsWith(term) } } },
                ),
            )
            .take(limit)
            .map { it.text }
            .toList()
    }

    companion object {
        private const val ASSET = "emoji/emoji.txt"
        private const val PREFS = "whistleflux"
        private const val RECENT_KEY = "recentEmoji"
        private const val MAX_RECENT = 32

        @Volatile private var cache: EmojiData? = null

        /** Loads once (about a tenth of a second); call off the main thread first if you can. */
        fun get(context: Context): EmojiData {
            cache?.let { return it }
            val paint = Paint()
            val list = ArrayList<Emoji>()
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    if (f.size < 2 || !paint.hasGlyph(f[1])) continue
                    list += Emoji(f[1], f[0], f.getOrElse(2) { "" }, f.getOrElse(3) { "" })
                }
            }
            return EmojiData(list).also { cache = it }
        }

        fun recent(context: Context): List<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT_KEY, "").orEmpty()
                .split('\n').filter { it.isNotEmpty() }

        fun rememberRecent(context: Context, emoji: String) {
            val list = (listOf(emoji) + recent(context).filter { it != emoji }).take(MAX_RECENT)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(RECENT_KEY, list.joinToString("\n")) }
        }
    }
}

/**
 * One long scrolling grid of emoji: recently used first, then every group, each with a small
 * label. Drawn directly (only the visible rows), so the ~1,900 emoji scroll smoothly.
 */
@SuppressLint("ViewConstructor")
class EmojiGrid(
    context: Context,
    private val theme: KeyboardTheme,
    private val sections: List<Pair<String, List<String>>>,
    private val onPick: (String) -> Unit,
) : View(context) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val cell = dp(46f)
    private val labelHeight = dp(30f)
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        color = theme.muted
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.pressedOver(theme.background) }

    /** A row is either a section label or up to [columns] emoji. */
    private class Row(val top: Float, val label: String?, val items: List<String>)

    private var rows: List<Row> = emptyList()
    private var columns = 1
    private var contentHeight = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var pressed: Pair<Int, Int>? = null // row, column

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        columns = max(1, ((w - dp(8f)) / cell).toInt())
        val out = ArrayList<Row>()
        var y = 0f
        for ((label, emoji) in sections) {
            if (emoji.isEmpty()) continue
            out += Row(y, label, emptyList())
            y += labelHeight
            for (chunk in emoji.chunked(columns)) {
                out += Row(y, null, chunk)
                y += cell
            }
        }
        rows = out
        contentHeight = y + dp(8f)
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun maxScroll() = max(0f, contentHeight - height)

    private fun left() = (width - columns * cell) / 2

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val first = rows.indexOfFirst { it.top + (if (it.label != null) labelHeight else cell) > scroll }.coerceAtLeast(0)
        for (r in first until rows.size) {
            val row = rows[r]
            val y = row.top - scroll
            if (y > height) break
            if (row.label != null) {
                canvas.drawText(row.label, left() + dp(8f), y + labelHeight - dp(9f), labelPaint)
                continue
            }
            row.items.forEachIndexed { c, emoji ->
                val cx = left() + c * cell + cell / 2
                if (pressed == r to c) canvas.drawCircle(cx, y + cell / 2, cell / 2 - dp(2f), pressPaint)
                canvas.drawText(emoji, cx, y + cell / 2 - (emojiPaint.descent() + emojiPaint.ascent()) / 2, emojiPaint)
            }
        }
    }

    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val yy = y + scroll
        val r = rows.indexOfLast { it.top <= yy }
        val row = rows.getOrNull(r) ?: return null
        if (row.label != null) return null
        val c = ((x - left()) / cell).toInt()
        return if (c in row.items.indices && x >= left()) r to c else null
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            pressed = hit(e.x, e.y)
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            pressed = null
            scroll = (scroll + dy).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (r, c) = hit(e.x, e.y) ?: return true
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPick(rows[r].items[c])
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = null
            invalidate()
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = min(scroller.currY.toFloat(), maxScroll()).coerceAtLeast(0f)
            postInvalidateOnAnimation()
        }
    }
}
