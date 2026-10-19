package app.whistleflux.ime

/**
 * Key layouts: the mic view (the default), QWERTY, the symbols page, and number and phone
 * pads. Pure Kotlin geometry (no Android classes), so the swipe decoder can be tested on
 * the computer.
 */
enum class KeyType {
    /** Types its text. */
    CHAR,
    SHIFT, DELETE, ENTER, SPACE, MIC,
    /** Open the symbols page. */
    SYMBOLS,
    /** Back from the symbols page or number pad to the letters. */
    LETTERS_PAGE,
    /** From the mic view to QWERTY. */
    TO_QWERTY,
}

class Key(
    val type: KeyType,
    /** What a CHAR key types, or a mode key's label. */
    val text: String = "",
    /** Width in units of one key. */
    val width: Float = 1f,
    /** Typed on long-press, and drawn small in the corner. */
    val hint: String? = null,
    /** Long-press opens these to slide across and pick one (instead of [hint]). */
    val popup: List<String>? = null,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isLetter get() = type == KeyType.CHAR && text.length == 1 && text[0] in 'a'..'z'
    val isFunction get() = type != KeyType.CHAR && type != KeyType.SPACE

    /** Swipe typing starts on these. */
    val swipeable get() = isLetter

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * One row: keys plus empty space (in key units) before and after, e.g. QWERTY's "asdf" row.
 * [height] is relative to the layout's other rows (the mic view's mic row is taller).
 */
class Row(val keys: List<Key>, val inset: Float = 0f, val height: Float = 1f)

class Layout(val rows: List<Row>, val columns: Float, val rowHeightDp: Float = 54f, val voice: Boolean = false) {
    val keys get() = rows.flatMap { it.keys }

    /** Height in rows of [rowHeightDp]. */
    val heightInRows get() = rows.sumOf { it.height.toDouble() }.toFloat()

    /**
     * Places the keys in [left, top, right, bottom]. Each key fills its whole cell (gaps
     * are drawn, not hit-tested), and [keyAt] maps touches beside a row's ends to the
     * nearest key.
     */
    fun place(left: Float, top: Float, right: Float, bottom: Float) {
        val unit = (right - left) / columns
        val rowUnit = (bottom - top) / heightInRows
        var y = top
        for (row in rows) {
            val used = row.keys.sumOf { it.width.toDouble() }.toFloat() + 2 * row.inset
            var x = left + row.inset * unit + (columns - used) * unit / 2
            val h = row.height * rowUnit
            for (key in row.keys) {
                key.left = x
                key.top = y
                x += key.width * unit
                key.right = x
                key.bottom = y + h
            }
            y += h
        }
    }

    /** The key under (x, y), or the nearest key in that row. */
    fun keyAt(x: Float, y: Float): Key? {
        val row = rows.firstOrNull { r -> r.keys.isNotEmpty() && y >= r.keys[0].top && y < r.keys[0].bottom }
            ?: return null
        return row.keys.firstOrNull { it.contains(x, y) }
            ?: row.keys.minByOrNull { minOf(kotlin.math.abs(it.left - x), kotlin.math.abs(it.right - x)) }
    }

    /** For swipe typing: the key each letter is on (on QWERTY, the letter itself). */
    fun keyOf(letter: Char): Char? = letterKeys[letter]

    private val letterKeys: Map<Char, Char> by lazy { keys.filter { it.isLetter }.associate { it.text[0] to it.text[0] } }

    /** Centers of the swipeable keys, by the id [keyOf] gives them. */
    fun swipeCenters(): Map<Char, FloatArray> = keys.filter { it.swipeable }.associate { it.text[0] to floatArrayOf(it.centerX, it.centerY) }

    /** Width of one swipeable key in pixels (after [place]). */
    fun unitWidth(): Float = keys.firstOrNull { it.swipeable }?.let { it.right - it.left } ?: 1f
}

object Layouts {
    /** Long-press "." on QWERTY (Gboard's punctuation popup). */
    val PERIOD_POPUP = listOf("*", "\"", "'", ":", ";", "!", "?")

    /** Long-press "," on QWERTY. */
    val COMMA_POPUP = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")

    /** The symbols key's label: a few of what it opens. */
    const val SYMBOLS_LABEL = "@#&"

    private fun chars(s: String, hints: String? = null) =
        s.mapIndexed { i, c -> Key(KeyType.CHAR, c.toString(), hint = hints?.getOrNull(i)?.toString()) }

    /**
     * The default view: a big mic (tap it to speak; it grows into the voice pill), and a row
     * to switch to QWERTY, type a space, delete and press enter.
     */
    fun voice(): Layout = Layout(
        listOf(
            Row(listOf(Key(KeyType.MIC, width = 10f)), height = 3f),
            Row(
                listOf(
                    Key(KeyType.TO_QWERTY, width = 1.5f),
                    Key(KeyType.SPACE, width = 5.5f),
                    Key(KeyType.DELETE, width = 1.5f),
                    Key(KeyType.ENTER, width = 1.5f),
                ),
            ),
        ),
        columns = 10f,
        voice = true,
    )

    /** QWERTY (the toolbar's keyboard button), with digits on the top row's long-press. */
    fun qwerty(comma: String = ","): Layout = Layout(
        listOf(
            Row(chars("qwertyuiop", "1234567890")),
            Row(chars("asdfghjkl"), inset = 0.5f),
            Row(listOf(Key(KeyType.SHIFT, width = 1.5f)) + chars("zxcvbnm") + Key(KeyType.DELETE, width = 1.5f)),
            Row(
                listOf(
                    Key(KeyType.SYMBOLS, SYMBOLS_LABEL, width = 1.5f),
                    Key(KeyType.CHAR, comma, popup = COMMA_POPUP),
                    Key(KeyType.SPACE, width = 4f),
                    Key(KeyType.CHAR, ".", popup = PERIOD_POPUP),
                    Key(KeyType.MIC),
                    Key(KeyType.ENTER, width = 1.5f),
                ),
            ),
        ),
        columns = 10f,
    )

    /** The symbols page: the common symbols, in big keys. */
    fun symbols(): Layout {
        val fn = 1.2f
        return Layout(
            listOf(
                Row(chars(".,?!'") + Key(KeyType.DELETE, width = fn)),
                Row(chars("\":;-_") + Key(KeyType.ENTER, width = fn)),
                Row(chars("@#\$&*") + Key(KeyType.MIC, width = fn)),
                Row(listOf(Key(KeyType.LETTERS_PAGE, "abc")) + chars("+()/") + Key(KeyType.SPACE, width = fn)),
            ),
            columns = 5f + fn,
        )
    }

    /** Number pad (the toolbar's number pad button, and number fields), like Gboard's. */
    fun numbers(): Layout = Layout(
        listOf(
            Row(chars("+123/")),
            Row(chars("-456") + Key(KeyType.SPACE)),
            Row(chars("*789") + Key(KeyType.DELETE)),
            Row(listOf(Key(KeyType.LETTERS_PAGE, "abc")) + chars(",0.") + Key(KeyType.ENTER)),
        ),
        columns = 5f,
    )

    /** Dial pad for phone fields. */
    fun phone(): Layout = Layout(
        listOf(
            Row(chars("123") + Key(KeyType.CHAR, "+")),
            Row(chars("456") + Key(KeyType.SPACE)),
            Row(chars("789") + Key(KeyType.DELETE)),
            Row(chars("*0#") + Key(KeyType.ENTER)),
        ),
        columns = 4f,
    )
}
