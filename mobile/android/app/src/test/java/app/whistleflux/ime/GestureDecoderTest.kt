package app.whistleflux.ime

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulated swipes over phone-sized layouts, decoded with the real word list. */
class GestureDecoderTest {
    // Unit tests run from mobile/android/app; the build generates this file first.
    private val dictionary = File("build/generated/dictionary/assets/${Dictionary.ASSET}")
        .bufferedReader().useLines { Dictionary.parse(it) }

    // 1080 px wide at 3x density, like a typical phone: QWERTY has four 54 dp rows.
    private val layout = Layouts.qwerty().apply { place(0f, 0f, 1080f, 648f) }
    private val index = dictionary.swipeIndex("qwerty", layout::keyOf)
    private val centers = layout.swipeCenters()
    private val keyWidth = layout.unitWidth()

    private fun decode(xs: FloatArray, ys: FloatArray) = GestureDecoder.decode(xs, ys, index, centers, keyWidth)

    /** A sloppy swipe: each key point wobbles by up to [wobble] key widths, with jitter in between. */
    private fun swipe(word: String, random: Random, wobble: Float = 0.25f): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val keys = index.keysFor(word)!!
        val points = keys.map { c ->
            val center = centers.getValue(c)
            floatArrayOf(
                center[0] + (random.nextFloat() * 2 - 1) * wobble * keyWidth,
                center[1] + (random.nextFloat() * 2 - 1) * wobble * keyWidth,
            )
        }
        for (i in 0 until points.size - 1) {
            for (s in 0 until 8) {
                val t = s / 8f
                xs += points[i][0] + (points[i + 1][0] - points[i][0]) * t + random.nextFloat() * 6 - 3
                ys += points[i][1] + (points[i + 1][1] - points[i][1]) * t + random.nextFloat() * 6 - 3
            }
        }
        xs += points.last()[0]
        ys += points.last()[1]
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private val words = listOf(
        "the", "and", "that", "have", "with", "this", "from", "they", "would", "there",
        "their", "what", "about", "which", "when", "make", "like", "time", "just", "know",
        "take", "people", "into", "year", "good", "some", "could", "them", "see", "other",
        "than", "then", "now", "look", "only", "come", "over", "think", "also", "back",
        "after", "work", "first", "well", "way", "even", "want", "because", "any", "these",
        "give", "most", "hello", "meeting", "tomorrow", "thanks", "please", "message", "phone", "keyboard",
        "morning", "evening", "dinner", "office", "coffee", "weekend", "friday", "monday", "great", "sounds",
        "where", "going", "doing", "really", "today", "later", "maybe", "sorry", "right", "home",
    )

    @Test
    fun decodesCommonWords() {
        val random = Random(42)
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        for (word in words) {
            val (xs, ys) = swipe(word, random)
            val got = decode(xs, ys).map { it.lowercase() }
            if (got.firstOrNull() == word) top1++ else misses += "$word -> ${got.take(3)}"
            if (word in got.take(3)) top3++
        }
        println("top-1 $top1/${words.size}, top-3 $top3/${words.size}; misses: $misses")
        assertTrue("top-1 $top1/${words.size}: $misses", top1 >= words.size * 0.85)
        assertTrue("top-3 $top3/${words.size}: $misses", top3 >= words.size * 0.95)
    }

    @Test
    fun sloppySwipesStillFindTheWord() {
        val random = Random(7)
        var top3 = 0
        for (word in words) {
            val (xs, ys) = swipe(word, random, wobble = 0.4f)
            if (word in decode(xs, ys).take(3).map { it.lowercase() }) top3++
        }
        println("sloppy top-3 $top3/${words.size}")
        assertTrue("sloppy top-3 $top3/${words.size}", top3 >= words.size * 0.85)
    }

    @Test
    fun aTapIsNotASwipe() {
        val c = centers.getValue('g')
        assertEquals(emptyList<String>(), decode(floatArrayOf(c[0], c[0] + 2), floatArrayOf(c[1], c[1])))
    }

    @Test
    fun learnedWordsWin() {
        // A word you type often moves up the suggestions...
        val usual = dictionary.complete("tho", 3)
        val rare = dictionary.complete("tho", 50).last()
        dictionary.find(rare)!!.personal = 250
        assertEquals(rare, dictionary.complete("tho", 1).first())
        // ...and a new word (a name) becomes a suggestion and swipeable once learned.
        dictionary.findOrAdd("Whistleflux").personal = 165
        assertEquals("Whistleflux", dictionary.complete("whistlef", 1).first())
        assertTrue(index.candidates('w', 'x').any { it.word.text == "Whistleflux" })
        dictionary.forgetPersonal()
        assertEquals(usual, dictionary.complete("tho", 3))
    }

    @Test
    fun completesPrefixesByFrequency() {
        val got = dictionary.complete("tho", 3)
        assertEquals(3, got.size)
        assertTrue(got.toString(), "those" in got || "though" in got || "thought" in got)
    }
}
