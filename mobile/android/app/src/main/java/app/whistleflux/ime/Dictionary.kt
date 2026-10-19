package app.whistleflux.ime

import android.content.Context
import kotlin.math.max

/**
 * English word list with frequencies (0..255), from AOSP's keyboard (Apache 2.0), plus the
 * words this phone has learned (see [Learning]). The build downloads and filters the list
 * into assets/dictionaries/en_words.txt as "word<TAB>frequency" lines (see
 * prepareDictionary in android/app/build.gradle).
 *
 * Not thread-safe: build it anywhere, then use it from one thread.
 */
class Dictionary(entries: List<Pair<String, Int>>) {
    class Word(val text: String, val base: Int) {
        val lower: String = text.lowercase()

        /** From [Learning]: how often you type it, on the same 0..255 scale. */
        var personal = 0

        /** What ranking uses: the higher of the list's frequency and yours. */
        val score get() = max(base, personal)
    }

    /** Words indexed for swipe typing on one layout. */
    class SwipeIndex(private val keyOf: (Char) -> Char?) {
        class Entry(val word: Word, val keys: String)

        private val byEnds = HashMap<Int, MutableList<Entry>>()

        /** The keys a swipe passes through: letters mapped to keys, repeats once. Null if not swipeable. */
        fun keysFor(word: String): String? {
            val sb = StringBuilder()
            for (c in word.lowercase()) {
                if (c == '\'') continue
                val k = keyOf(c) ?: return null
                if (sb.isEmpty() || sb.last() != k) sb.append(k)
            }
            return sb.takeIf { it.length >= 2 }?.toString()
        }

        fun add(word: Word) {
            val keys = keysFor(word.text) ?: return
            byEnds.getOrPut(ends(keys.first(), keys.last())) { ArrayList() } += Entry(word, keys)
        }

        fun candidates(first: Char, last: Char): List<Entry> = byEnds[ends(first, last)].orEmpty()

        private fun ends(first: Char, last: Char) = (first.code shl 16) or last.code
    }

    private val sorted = ArrayList<Word>()
    private val byLower = HashMap<String, Word>()
    private val swipeIndexes = HashMap<String, SwipeIndex>()

    init {
        for ((text, freq) in entries) add(Word(text, freq), keepSorted = false)
        sorted.sortBy { it.lower }
    }

    val size get() = sorted.size

    private fun add(word: Word, keepSorted: Boolean = true) {
        val existing = byLower[word.lower]
        if (existing == null || word.base > existing.base) byLower[word.lower] = word
        if (keepSorted) {
            val at = lowerBound(word.lower)
            sorted.add(at, word)
        } else {
            sorted += word
        }
        for (index in swipeIndexes.values) index.add(word)
    }

    /** The word as listed (case-insensitive), if it is. */
    fun find(word: String): Word? = byLower[word.lowercase()]

    /** Adds a word you typed that isn't in the list (a name, say), or returns the listed one. */
    fun findOrAdd(word: String): Word = find(word) ?: Word(word, 0).also { add(it) }

    /** Forgets everything learned: personal scores back to 0, learned-only words out of ranking. */
    fun forgetPersonal() {
        for (w in sorted) w.personal = 0
    }

    /** Swipe index for a layout, built on first use (about 100 ms) and kept up to date. */
    fun swipeIndex(name: String, keyOf: (Char) -> Char?): SwipeIndex = swipeIndexes.getOrPut(name) {
        SwipeIndex(keyOf).also { index -> sorted.forEach { if (it.score > 0) index.add(it) } }
    }

    /** The highest-ranked words starting with [prefix] (case-insensitive), best first. */
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val best = ArrayList<Word>()
        var i = lowerBound(lower)
        while (i < sorted.size && sorted[i].lower.startsWith(lower)) {
            val w = sorted[i++]
            if (w.score > 0) best += w
        }
        return best.sortedByDescending { it.score }.take(limit).map { it.text }
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].lower < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val ASSET = "dictionaries/en_words.txt"

        fun parse(lines: Sequence<String>): Dictionary = Dictionary(
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).trim().toIntOrNull() ?: 0)
            }.toList(),
        )

        /** Reads the bundled word list. Takes a few hundred milliseconds: call off the main thread. */
        fun load(context: Context): Dictionary =
            context.assets.open(ASSET).bufferedReader().useLines { parse(it) }
    }
}
