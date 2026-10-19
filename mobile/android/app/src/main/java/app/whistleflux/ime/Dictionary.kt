package app.whistleflux.ime

import android.content.Context

/**
 * English word list with frequencies (0..255), from AOSP's keyboard (Apache 2.0). The build
 * downloads and filters it into assets/dictionaries/en_words.txt as "word<TAB>frequency"
 * lines (see prepareDictionary in android/app/build.gradle).
 */
class Dictionary(entries: List<Pair<String, Int>>) {
    class Word(val text: String, val freq: Int) {
        val lower: String = text.lowercase()

        /** The letter keys a swipe passes through: letters only, doubled letters once. */
        val keys: String = buildString {
            for (c in lower) if (c in 'a'..'z' && (isEmpty() || last() != c)) append(c)
        }
    }

    private val sorted: Array<Word> = entries.map { Word(it.first, it.second) }.sortedBy { it.lower }.toTypedArray()

    /** Words a swipe can produce (two or more distinct keys), by first and last key. */
    private val byEnds: Map<Int, List<Word>> = sorted.filter { it.keys.length >= 2 }
        .groupBy { endsKey(it.keys.first(), it.keys.last()) }
        .mapValues { (_, words) -> words.sortedByDescending { it.freq } }

    val size get() = sorted.size

    fun swipeCandidates(first: Char, last: Char): List<Word> = byEnds[endsKey(first, last)].orEmpty()

    fun frequency(word: String): Int? {
        val lower = word.lowercase()
        var i = lowerBound(lower)
        var best: Int? = null
        while (i < sorted.size && sorted[i].lower == lower) {
            best = maxOf(best ?: 0, sorted[i].freq)
            i++
        }
        return best
    }

    /** The most frequent words starting with [prefix] (case-insensitive), most frequent first. */
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val best = ArrayList<Word>(limit + 1)
        var i = lowerBound(lower)
        while (i < sorted.size && sorted[i].lower.startsWith(lower)) {
            val w = sorted[i++]
            if (best.size < limit || w.freq > best.last().freq) {
                best.add(best.indexOfFirst { it.freq < w.freq }.let { if (it < 0) best.size else it }, w)
                if (best.size > limit) best.removeAt(best.lastIndex)
            }
        }
        return best.map { it.text }
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

        private fun endsKey(first: Char, last: Char) = (first - 'a') * 26 + (last - 'a')

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
