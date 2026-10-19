package app.whistleflux.ime

import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a swipe across the letter keys into words.
 *
 * For each word whose first and last keys are near the swipe's ends, it builds the word's
 * ideal path (straight lines through its key centers) and compares it with the swipe:
 * - location: how far apart the two paths are, point by point, in key widths,
 * - shape: the same after moving both to the origin and scaling them to the same size,
 * plus a penalty for rare words (your own usage counts, see [Learning]). Words with a key
 * the swipe never came near are skipped.
 * The lowest total wins. Pure Kotlin, so it is tested on the computer (GestureDecoderTest).
 */
object GestureDecoder {
    /**
     * xs, ys: the swipe's points, in the same coordinates as [centers] (key id -> x, y, with
     * the ids [index] uses). Returns up to [limit] words, best first.
     */
    fun decode(
        xs: FloatArray, ys: FloatArray, index: Dictionary.SwipeIndex, centers: Map<Char, FloatArray>, keyWidth: Float, limit: Int = 4,
    ): List<String> {
        if (xs.size < 2 || pathLength(xs, ys) < keyWidth * 0.5f) return emptyList()
        val (px, py) = resample(xs, ys, POINTS)

        val firsts = nearKeys(px[0], py[0], centers, keyWidth * END_RADIUS)
        val lasts = nearKeys(px[POINTS - 1], py[POINTS - 1], centers, keyWidth * END_RADIUS)
        val userShape = normalized(px, py, keyWidth)

        val scored = ArrayList<Pair<Dictionary.Word, Float>>()
        val ix = FloatArray(POINTS)
        val iy = FloatArray(POINTS)
        for (f in firsts) for (l in lasts) {
            for (entry in index.candidates(f, l)) {
                val word = entry.word
                if (word.score <= 0) continue
                if (!passesNear(entry.keys, px, py, centers, keyWidth * PASS_RADIUS)) continue
                idealPath(entry.keys, centers, ix, iy)
                var loc = 0f
                for (i in 0 until POINTS) loc += hypot(px[i] - ix[i], py[i] - iy[i])
                loc /= POINTS * keyWidth
                val shape = shapeDistance(userShape, normalized(ix, iy, keyWidth))
                val rarity = (MAX_FREQ - minOf(word.score, MAX_FREQ)).toFloat() / MAX_FREQ
                scored += word to (loc + SHAPE_WEIGHT * shape + RARITY_WEIGHT * rarity)
            }
        }
        scored.sortBy { it.second }
        val out = LinkedHashSet<String>()
        for ((word, _) in scored) {
            out += word.text
            if (out.size == limit) break
        }
        return out.toList()
    }

    private fun idealPath(keys: String, centers: Map<Char, FloatArray>, outX: FloatArray, outY: FloatArray) {
        val kx = FloatArray(keys.length) { centers.getValue(keys[it])[0] }
        val ky = FloatArray(keys.length) { centers.getValue(keys[it])[1] }
        val (rx, ry) = resample(kx, ky, POINTS)
        rx.copyInto(outX)
        ry.copyInto(outY)
    }

    private fun passesNear(keys: String, px: FloatArray, py: FloatArray, centers: Map<Char, FloatArray>, radius: Float): Boolean {
        for (c in keys) {
            val center = centers[c] ?: return false
            var near = false
            for (i in px.indices) {
                if (hypot(px[i] - center[0], py[i] - center[1]) < radius) {
                    near = true
                    break
                }
            }
            if (!near) return false
        }
        return true
    }

    private fun nearKeys(x: Float, y: Float, centers: Map<Char, FloatArray>, radius: Float): List<Char> {
        val near = centers.filter { (_, c) -> hypot(c[0] - x, c[1] - y) < radius }.keys
        return near.ifEmpty { setOf(centers.minBy { (_, c) -> hypot(c[0] - x, c[1] - y) }.key) }.toList()
    }

    /** Points moved so their center is at the origin and scaled so the larger side is 1. */
    private fun normalized(xs: FloatArray, ys: FloatArray, keyWidth: Float): Pair<FloatArray, FloatArray> {
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        // A floor on the size keeps short swipes from being blown up into big shapes.
        val size = max(max(maxX - minX, maxY - minY), keyWidth)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        return FloatArray(xs.size) { (xs[it] - cx) / size } to FloatArray(ys.size) { (ys[it] - cy) / size }
    }

    private fun shapeDistance(a: Pair<FloatArray, FloatArray>, b: Pair<FloatArray, FloatArray>): Float {
        var d = 0f
        for (i in a.first.indices) d += hypot(a.first[i] - b.first[i], a.second[i] - b.second[i])
        return d / a.first.size
    }

    const val POINTS = 48
    private const val END_RADIUS = 1.1f // key widths from a swipe end to its first/last key
    private const val PASS_RADIUS = 1.0f // how close the swipe must come to every key
    private const val SHAPE_WEIGHT = 1.5f
    private const val RARITY_WEIGHT = 0.6f
    private const val MAX_FREQ = 230

    fun pathLength(xs: FloatArray, ys: FloatArray): Float {
        var len = 0f
        for (i in 1 until xs.size) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return len
    }

    /** [n] points spaced evenly along the path (all at the start if it has no length). */
    fun resample(xs: FloatArray, ys: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
        val outX = FloatArray(n)
        val outY = FloatArray(n)
        val total = pathLength(xs, ys)
        if (xs.size == 1 || total == 0f) {
            outX.fill(xs[0])
            outY.fill(ys[0])
            return outX to outY
        }
        val step = total / (n - 1)
        var seg = 1
        var segStart = 0f // distance along the path where segment [seg-1, seg] starts
        var segLen = hypot(xs[1] - xs[0], ys[1] - ys[0])
        for (i in 0 until n) {
            val target = minOf(i * step, total)
            while (seg < xs.size - 1 && segStart + segLen < target) {
                segStart += segLen
                seg++
                segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
            }
            val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            outX[i] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
            outY[i] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
        }
        return outX to outY
    }
}
