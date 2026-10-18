package app.sayflux.engine

/**
 * Basic clean-up of a transcript with simple rules: no AI, no network, instant.
 *
 * Removes filler sounds and stutters, applies "scratch that", and turns "new line" and
 * "new paragraph" into line breaks. It only deletes or moves text the speaker clearly
 * didn't mean to keep, so it never changes the meaning.
 *
 * Line-for-line port of the desktop app's src/sayflux/cleanup.py. Both are checked
 * against shared/cleanup-cases.json, so change them together.
 */
object Cleanup {
    // um, ummm, uh, uhm, er, erm, ah, hmm, mm, mhm (any number of repeated letters)
    private val FILLER = Regex("(?:u+m+|u+h+m*|e+r+m*|a+h+|h+m+|m{2,}|m+h+m+)")

    // Words people repeat on purpose ("no, no", "had had", "that that").
    private val KEEP_REPEATED = setOf("no", "yes", "very", "so", "that", "had", "ha", "bye", "really", "well", "go")

    // "a new line of products" is not a command.
    private val NOT_BEFORE_COMMAND =
        setOf("a", "an", "the", "this", "that", "one", "each", "every", "another", "of", "my", "our", "your")

    private const val SENTENCE_END = ".!?"
    private const val EDGE_PUNCT = ".,!?;:\"'()[]"

    private val SCRATCH = Regex("\\bscratch that\\b[.,!?]*", RegexOption.IGNORE_CASE)
    private val COMMAND = Regex("\\bnew (line|paragraph)\\b", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val LEADING_JUNK = Regex("^[,;:\\s]+")

    fun clean(input: String): String {
        var text = applyScratchThat(input)
        text = applyCommands(text)
        val lines = text.split("\n").map(::cleanLine)
        // Start each new line with a capital, like a new sentence.
        return lines.mapIndexed { n, line -> if (n > 0) capitalize(line) else line }
            .joinToString("\n")
            .trim()
    }

    private fun core(token: String) = token.trim { it in EDGE_PUNCT }.lowercase()

    private fun words(text: String) = text.split(WHITESPACE).filter { it.isNotEmpty() }

    private fun capitalize(text: String): String {
        val i = text.indexOfFirst { it.isLetter() }
        if (i < 0) return text
        return text.substring(0, i) + text[i].uppercase() + text.substring(i + 1)
    }

    /** Delete the sentence (or start of the text) before each "scratch that". */
    private fun applyScratchThat(input: String): String {
        var text = input
        while (true) {
            val m = SCRATCH.find(text) ?: return text
            val before = text.substring(0, m.range.first).trimEnd().trimEnd { it in ".,!?;: " }
            val cut = before.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            val kept = if (cut >= 0) before.substring(0, cut + 1) else ""
            var after = text.substring(m.range.last + 1).trimStart()
            if (kept.isEmpty() || kept.last() in "$SENTENCE_END\n") after = capitalize(after)
            text = "$kept $after".trim()
        }
    }

    private fun applyCommands(text: String): String {
        val out = StringBuilder()
        var pos = 0
        for (m in COMMAND.findAll(text)) {
            val previous = words(text.substring(0, m.range.first))
            if (previous.isNotEmpty() && core(previous.last()) in NOT_BEFORE_COMMAND) continue
            out.append(text.substring(pos, m.range.first).trimEnd { it == ' ' || it == ',' })
            out.append(if (m.groupValues[1].lowercase() == "line") "\n" else "\n\n")
            pos = m.range.last + 1
            while (pos < text.length && text[pos] in " ,.") pos++
        }
        out.append(text.substring(pos))
        return out.toString()
    }

    private fun cleanLine(line: String): String {
        val tokens = words(line)
        val kept = ArrayList<String>()
        var capitalizeNext = false // set when we drop words at the start of a sentence
        var i = 0
        while (i < tokens.size) {
            var tok = tokens[i]
            val atStart = kept.isEmpty() || kept.last().last() in SENTENCE_END
            val core = core(tok)

            if (FILLER.matches(core)) {
                // Keep sentence-ending punctuation that was attached to the filler: "coffee uh."
                if (tok.last() in SENTENCE_END && kept.isNotEmpty()) {
                    kept[kept.size - 1] = kept.last().trimEnd { it in ",;:" }
                    if (kept.last().isEmpty()) kept.removeAt(kept.size - 1) // it was just a comma
                    if (kept.isNotEmpty() && kept.last().last() !in SENTENCE_END) {
                        kept[kept.size - 1] = kept.last() + tok.last()
                    }
                }
                if (atStart) capitalizeNext = true
                i++
                continue
            }

            // Stutters: "the the", "I, I think", "I think I think". Drop the first copy.
            var skip = 0
            for (n in intArrayOf(3, 2, 1)) {
                if (i + 2 * n > tokens.size || core.isEmpty()) continue
                val a = tokens.subList(i, i + n)
                val b = tokens.subList(i + n, i + 2 * n)
                if (a.map(::core) != b.map(::core)) continue
                if (n == 1 && core in KEEP_REPEATED) continue
                if (a.last().last() in SENTENCE_END) continue // "Yes. Yes." is two sentences
                skip = n
                break
            }
            if (skip > 0) {
                if (atStart) capitalizeNext = true
                i += skip
                continue
            }

            if (capitalizeNext) {
                tok = capitalize(tok)
                capitalizeNext = false
            }
            kept.add(tok)
            i++
        }
        return kept.joinToString(" ").replace(LEADING_JUNK, "")
    }
}
