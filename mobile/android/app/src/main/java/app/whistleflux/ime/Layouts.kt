package app.whistleflux.ime

/**
 * Key layouts, modeled on Gboard: QWERTY letters (long-press the top row for digits), two
 * symbol pages, and a number pad for number and phone fields. Pure Kotlin geometry (no
 * Android classes), so the swipe decoder can be tested on the computer.
 */
enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, LETTERS, MORE_SYMBOLS }

class Key(
    val type: KeyType,
    /** What a CHAR key types (lowercase for letters), or the label of a mode key. */
    val text: String = "",
    /** Width in units of one letter key. */
    val width: Float = 1f,
    /** Typed on long-press, and drawn small in the corner. */
    val hint: String? = null,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isLetter get() = type == KeyType.CHAR && text.length == 1 && text[0] in 'a'..'z'
    val isFunction get() = type != KeyType.CHAR && type != KeyType.SPACE

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/** One row: keys plus empty space (in key units) before and after, e.g. QWERTY's "asdf" row. */
class Row(val keys: List<Key>, val inset: Float = 0f)

class Layout(val rows: List<Row>, val columns: Float) {
    val keys get() = rows.flatMap { it.keys }

    /**
     * Places the keys in [left, top, right, bottom]. Each key fills its whole cell (gaps
     * are drawn, not hit-tested), and [keyAt] maps touches beside a row's ends to the
     * nearest key.
     */
    fun place(left: Float, top: Float, right: Float, bottom: Float) {
        val unit = (right - left) / columns
        val rowHeight = (bottom - top) / rows.size
        rows.forEachIndexed { r, row ->
            val used = row.keys.sumOf { it.width.toDouble() }.toFloat() + 2 * row.inset
            var x = left + row.inset * unit + (columns - used) * unit / 2
            row.keys.forEach { key ->
                key.left = x
                key.top = top + r * rowHeight
                x += key.width * unit
                key.right = x
                key.bottom = key.top + rowHeight
            }
        }
    }

    /** The key under (x, y), or the nearest key in that row. */
    fun keyAt(x: Float, y: Float): Key? {
        val row = rows.firstOrNull { r -> r.keys.isNotEmpty() && y >= r.keys[0].top && y < r.keys[0].bottom }
            ?: return null
        return row.keys.firstOrNull { it.contains(x, y) }
            ?: row.keys.minByOrNull { minOf(kotlin.math.abs(it.left - x), kotlin.math.abs(it.right - x)) }
    }

    /** Centers of the letter keys, for the swipe decoder. */
    fun letterCenters(): Map<Char, FloatArray> =
        keys.filter { it.isLetter }.associate { it.text[0] to floatArrayOf(it.centerX, it.centerY) }

    /** Width of one letter key in pixels (after [place]). */
    fun unitWidth(): Float = keys.firstOrNull { it.isLetter }?.let { it.right - it.left } ?: 1f
}

object Layouts {
    private fun chars(s: String, hints: String? = null) =
        s.mapIndexed { i, c -> Key(KeyType.CHAR, c.toString(), hint = hints?.getOrNull(i)?.toString()) }

    /** [comma] is "," normally, "@" in email fields and "/" in URL fields. */
    fun letters(comma: String = ","): Layout = Layout(
        listOf(
            Row(chars("qwertyuiop", "1234567890")),
            Row(chars("asdfghjkl"), inset = 0.5f),
            Row(listOf(Key(KeyType.SHIFT, width = 1.5f)) + chars("zxcvbnm") + Key(KeyType.DELETE, width = 1.5f)),
            bottomRow(Key(KeyType.SYMBOLS, "?123", 1.5f), comma, "."),
        ),
        columns = 10f,
    )

    fun symbols(comma: String = ","): Layout = Layout(
        listOf(
            Row(chars("1234567890")),
            Row(chars("@#\$_&-+()/")),
            Row(listOf(Key(KeyType.MORE_SYMBOLS, "=\\<", 1.5f)) + chars("*\"':;!?") + Key(KeyType.DELETE, width = 1.5f)),
            bottomRow(Key(KeyType.LETTERS, "ABC", 1.5f), comma, "."),
        ),
        columns = 10f,
    )

    fun moreSymbols(): Layout = Layout(
        listOf(
            Row(chars("~`|•√π÷×¶∆")),
            Row(chars("£¢€¥^°={}\\")),
            Row(listOf(Key(KeyType.SYMBOLS, "?123", 1.5f)) + chars("%©®™✓[]") + Key(KeyType.DELETE, width = 1.5f)),
            bottomRow(Key(KeyType.LETTERS, "ABC", 1.5f), "<", ">"),
        ),
        columns = 10f,
    )

    /** Number pad for number fields; [phone] swaps in the dialer's * # and +. */
    fun numbers(phone: Boolean): Layout = Layout(
        listOf(
            Row(chars("123") + Key(KeyType.CHAR, if (phone) "+" else "-")),
            Row(chars("456") + Key(KeyType.SPACE)),
            Row(chars("789") + Key(KeyType.DELETE)),
            Row(
                if (phone) chars("*0#") + Key(KeyType.ENTER)
                else chars(",0.") + Key(KeyType.ENTER),
            ),
        ),
        columns = 4f,
    )

    private fun bottomRow(mode: Key, comma: String, period: String) = Row(
        listOf(
            mode,
            Key(KeyType.CHAR, comma),
            Key(KeyType.SPACE, width = 5f),
            Key(KeyType.CHAR, period),
            Key(KeyType.ENTER, width = 1.5f),
        ),
    )
}
