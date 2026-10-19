package app.whistleflux.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.min

/**
 * What the keyboard learns from your typing, kept only on this phone (in the app's private
 * files, never sent anywhere):
 * - words you type, with counts: new words (names, slang) become suggestions and swipeable,
 *   and words you use often rank higher;
 * - which word you type after which, for next-word suggestions.
 * Nothing is learned in password fields or incognito fields. Use from the main thread.
 */
class Learning private constructor(private val file: File) {
    private class Seen(var text: String, var count: Int)

    private val words = HashMap<String, Seen>() // lowercase -> as typed, count
    private val pairs = HashMap<String, HashMap<String, Int>>() // previous (lowercase) -> next word -> count
    private val main = Handler(Looper.getMainLooper())
    private val saveLater = Runnable { save() }

    /** Called after [forget], so the keyboard can reset its ranking. */
    var onForgotten: (() -> Unit)? = null

    /** Your score for a word, on the word list's 0..255 scale: 165 after one use, 240 after 15. */
    fun score(word: String): Int {
        val count = words[word.lowercase()]?.count ?: return 0
        return min(250, (140 + 36 * ln((count + 1).toDouble())).toInt())
    }

    /** Every learned word as you typed it, with its score. */
    fun all(): List<Pair<String, Int>> = words.values.map { it.text to score(it.text) }

    /** Learns [word], typed after [previous] (null at the start of a sentence). */
    fun learn(word: String, previous: String?) {
        val lower = word.lowercase()
        val seen = words.getOrPut(lower) { Seen(word, 0) }
        seen.count++
        // Keep the plain form once it's been typed in lowercase ("Hello" at a sentence start
        // is still "hello"); a word only ever typed capitalized (a name) stays capitalized.
        if (word == lower) seen.text = lower
        if (previous != null) {
            val next = pairs.getOrPut(previous.lowercase()) { HashMap() }
            next[seen.text] = (next[seen.text] ?: 0) + 1
            if (next.size > MAX_NEXT) next.remove(next.minBy { it.value }.key)
        }
        if (words.size > MAX_WORDS) words.remove(words.minBy { it.value.count }.key)
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, SAVE_DELAY_MS)
    }

    /** The words you most often type after [previous], best first. */
    fun next(previous: String, limit: Int): List<String> =
        pairs[previous.lowercase()].orEmpty().entries.sortedByDescending { it.value }.take(limit).map { it.key }

    private fun forgetAll() {
        main.removeCallbacks(saveLater)
        words.clear()
        pairs.clear()
        io.execute { file.delete() }
        onForgotten?.invoke()
    }

    private fun save() {
        val lines = ArrayList<String>(words.size + pairs.size * 4)
        for (seen in words.values) lines += "w\t${seen.text}\t${seen.count}"
        for ((previous, next) in pairs) for ((word, count) in next) lines += "p\t$previous\t$word\t$count"
        io.execute {
            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(lines.joinToString("\n"))
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't save learned words", e)
            }
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "w" -> words[f[1].lowercase()] = Seen(f[1], f[2].toIntOrNull() ?: 1)
                    f.size == 4 && f[0] == "p" -> pairs.getOrPut(f[1]) { HashMap() }[f[2]] = f[3].toIntOrNull() ?: 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read learned words", e)
        }
    }

    companion object {
        private const val TAG = "WhistlefluxLearning"
        private const val FILE = "learned_words.tsv"
        private const val MAX_WORDS = 20_000
        private const val MAX_NEXT = 30
        private const val SAVE_DELAY_MS = 3000L
        private val io = Executors.newSingleThreadExecutor()
        private var instance: Learning? = null

        /** The phone's learned words, loaded once. Call from the main thread. */
        fun get(context: Context): Learning =
            instance ?: Learning(File(context.applicationContext.filesDir, FILE)).also {
                it.load()
                instance = it
            }

        /** "Forget learned words" in the setup app. */
        fun forget(context: Context) {
            val learning = instance
            if (learning != null) {
                Handler(Looper.getMainLooper()).post { learning.forgetAll() }
            } else {
                io.execute { File(context.applicationContext.filesDir, FILE).delete() }
            }
        }
    }
}
