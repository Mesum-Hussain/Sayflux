package app.whistleflux.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import app.whistleflux.R
import app.whistleflux.engine.Dictation
import app.whistleflux.engine.Dictation.State
import app.whistleflux.engine.ModelManager
import java.util.concurrent.Executors

/**
 * The Whistleflux keyboard. It opens on a big mic: tap it and it grows into the voice pill;
 * speech is transcribed on the phone, cleaned up (rules, then Gemini if set up) and typed at
 * the cursor. The keyboard button switches to QWERTY (which opens by itself for passwords,
 * emails and web addresses), with swipe typing, word suggestions that learn from your typing
 * (on the phone only, see [Learning]), a symbols page, and a space bar that turns the
 * keyboard into a trackpad. The toolbar has emoji (with search) and clipboard panels, a
 * number pad, and undo/redo.
 *
 * Drawing and touches are in [KeyboardView]; this class does the typing.
 */
class WhistlefluxKeyboard : InputMethodService(), Dictation.Listener, KeyboardView.Listener, PanelListener {
    private lateinit var dictation: Dictation
    private lateinit var learning: Learning
    private val main = Handler(Looper.getMainLooper())
    private var view: KeyboardView? = null
    private var panelHost: FrameLayout? = null
    private var theme: KeyboardTheme? = null

    private var dictionary: Dictionary? = null

    private enum class Page { VOICE, QWERTY, SYMBOLS, NUMBERS, PHONE }
    private enum class Strip { NONE, COMPLETIONS, SWIPE, NEXT }

    private var page = Page.VOICE
    /** The page a field opens on, and "back" goes to: the mic, or QWERTY in password/email/address fields. */
    private var lettersPage = Page.VOICE
    /** The page the symbols page was opened from. */
    private var symbolsFrom = Page.QWERTY
    /** The page the number pad was opened from. */
    private var numbersFrom = Page.VOICE

    /** The emoji search query, typed on the keys; null when not searching. */
    private var search: StringBuilder? = null
    /** The page emoji search was opened from. */
    private var searchFrom = Page.VOICE
    private var searchResults: List<String> = emptyList()
    private var comma = ","
    /** Suggestions, swipe typing and the word-in-progress underline; off in password fields. */
    private var predict = true
    /** Off in password and incognito fields. */
    private var allowLearning = true

    /** The word being typed, as shown (underlined) in the field. */
    private val composing = StringBuilder()
    private var strip = Strip.NONE
    private var stripWords: MutableList<String> = mutableListOf()

    /** After a swiped word, the next word gets a space in front (Gboard's "phantom space"). */
    private var phantomSpace = false
    /** The swiped word just typed: delete removes it whole, and suggestions can replace it. */
    private var lastSwipe: String? = null
    /** Where the cursor was after typing [lastSwipe]; -1 if unknown. */
    private var swipeEnd = -1
    /** A swiped word is learned once you move on, so a wrong guess you delete isn't. */
    private var unlearnedSwipe: String? = null
    /** The last word typed in this sentence, for learning word pairs and next-word suggestions. */
    private var previousWord: String? = null
    /** The cursor (selection end) as last reported by the text field. */
    private var selEnd = -1

    /** Text copied in the last hour, newest first, in memory only. */
    private data class Clip(val text: String, val at: Long)
    private val clips = ArrayList<Clip>()
    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { rememberClip() }

    private var lastSpaceAt = 0L
    private var lastShiftAt = 0L
    private val clearMessage = Runnable { view?.message = null }

    override fun onCreate() {
        super.onCreate()
        dictation = Dictation(this, this)
        learning = Learning.get(this)
        learning.onForgotten = {
            dictionary?.forgetPersonal()
            previousWord = null
            setStrip(Strip.NONE)
        }
        // ~120,000 words: load and index once, off the main thread.
        Executors.newSingleThreadExecutor().execute {
            try {
                val d = Dictionary.load(this)
                d.swipeIndex(QWERTY_INDEX, Layouts.qwerty()::keyOf)
                EmojiData.get(this) // ready before the emoji panel first opens
                main.post {
                    for ((word, score) in learning.all()) d.findOrAdd(word).personal = score
                    dictionary = d
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load the word list", e)
            }
        }
        clipboard = getSystemService(ClipboardManager::class.java)?.also { it.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onCreateInputView(): View {
        val theme = KeyboardTheme(this).also { this.theme = it }
        val keyboard = KeyboardView(this, theme).also {
            it.listener = this
            view = it
        }
        // Panels (emoji, clipboard) go over the keys area, under the suggestion strip.
        val host = FrameLayout(this).also { panelHost = it }
        return FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboard.keysAreaHeight).apply {
                topMargin = keyboard.keysAreaTop
            })
        }
    }

    @SuppressLint("InlinedApi") // the incognito flag is new in Android 8; older fields just never set it
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = view ?: return
        val theme = KeyboardTheme(this).also { this.theme = it }
        v.applyTheme(theme)
        styleNavigationBar(theme)
        closePanel()
        endSearch()
        selEnd = info.initialSelEnd

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val text = inputClass == InputType.TYPE_CLASS_TEXT
        val password = text && variation in PASSWORD_VARIATIONS ||
            inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val address = text && variation in ADDRESS_VARIATIONS
        predict = text && !password
        allowLearning = predict && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        comma = when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
            InputType.TYPE_TEXT_VARIATION_URI -> "/"
            else -> ","
        }
        // Passwords and addresses are typed, not spoken: those fields start on QWERTY.
        lettersPage = if (password || address) Page.QWERTY else Page.VOICE
        numbersFrom = lettersPage
        page = when (inputClass) {
            InputType.TYPE_CLASS_PHONE -> Page.PHONE
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Page.NUMBERS
            else -> lettersPage
        }
        v.swipeEnabled = predict
        v.enterIcon = enterIcon(info)
        composing.clear()
        phantomSpace = false
        lastSwipe = null
        unlearnedSwipe = null
        previousWord = null
        setStrip(Strip.NONE)
        v.message = null
        v.exitVoice()
        showPage()
        updateShift()
        if (hasMicPermission()) dictation.warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation.cancel() // keyboard hidden or field changed: drop what was being said
        view?.exitVoice()
        view?.cancelTouches()
        closePanel()
        endSearch()
        finishWord()
        learnSwipe()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The cursor moved away from the word being typed (a tap in the text, say): let it go.
        if (composing.isNotEmpty() && (candidatesStart < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear()
            previousWord = null
            setStrip(Strip.NONE)
        }
        if (composing.isEmpty()) {
            // Keep the swiped word's suggestions while the cursor stays right after it. Checked
            // by position first, since some apps don't report the text before the cursor.
            val swiped = lastSwipe
            if (swiped != null) {
                val ours = (swipeEnd >= 0 && newSelStart == swipeEnd && newSelEnd == swipeEnd) ||
                    textBefore(swiped.length) == swiped
                if (!ours) {
                    Log.d(TAG, "cursor moved from the swiped word ($newSelStart, expected $swipeEnd)")
                    learnSwipe()
                    lastSwipe = null
                    phantomSpace = false
                    previousWord = null
                    if (strip == Strip.SWIPE) setStrip(Strip.NONE)
                }
            }
            updateShift()
        }
        selEnd = newSelEnd
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        learning.onForgotten = null
        dictation.destroy()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- KeyboardView.Listener: typing ----------------------------------------------------

    override fun onKey(key: Key) {
        if (search != null) return searchKey(key)
        when (key.type) {
            KeyType.CHAR -> typeText(if (key.isLetter && view?.shift != KeyboardView.Shift.OFF) key.text.uppercase() else key.text)
            KeyType.SHIFT -> toggleShift()
            KeyType.SPACE -> typeSpace()
            KeyType.ENTER -> {
                finishWord()
                learnSwipe()
                phantomSpace = false
                lastSwipe = null
                previousWord = null
                setStrip(Strip.NONE)
                sendKeyChar('\n') // runs the field's action (send, search...) or types a new line
                updateShift()
            }
            KeyType.DELETE -> onDelete()
            KeyType.MIC -> onMic()
            KeyType.SYMBOLS -> {
                symbolsFrom = if (page == Page.SYMBOLS || page == Page.NUMBERS) lettersPage else page
                setPage(Page.SYMBOLS)
            }
            KeyType.LETTERS_PAGE -> setPage(
                when (page) {
                    Page.SYMBOLS -> symbolsFrom
                    Page.NUMBERS -> numbersFrom
                    else -> lettersPage
                },
            )
            KeyType.TO_QWERTY -> setPage(Page.QWERTY)
        }
    }

    override fun onLongPressText(text: String) {
        val query = search
        if (query != null) {
            query.append(text)
            updateSearch()
        } else {
            typeText(text)
        }
    }

    /** Before a new word: learn the swiped one, and add the space a swipe left pending. */
    private fun startWord() {
        learnSwipe()
        if (phantomSpace) currentInputConnection?.commitText(" ", 1)
        phantomSpace = false
        lastSwipe = null
    }

    private fun typeText(text: String) {
        val ic = currentInputConnection ?: return
        val wordChar = text.length == 1 && (text[0].isLetter() || (text == "'" && composing.isNotEmpty()))
        if (predict && page == Page.QWERTY && wordChar) {
            if (composing.isEmpty()) startWord()
            composing.append(text)
            ic.setComposingText(composing, 1)
            if (view?.shift == KeyboardView.Shift.ON) view?.shift = KeyboardView.Shift.OFF
            showCompletions()
        } else {
            finishWord()
            learnSwipe()
            phantomSpace = false // punctuation sticks to a swiped word
            lastSwipe = null
            ic.commitText(text, 1)
            if (text in SENTENCE_ENDS) previousWord = null
            setStrip(Strip.NONE)
            if (view?.shift == KeyboardView.Shift.ON && text[0].isLetter()) view?.shift = KeyboardView.Shift.OFF
            updateShift()
        }
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        finishWord()
        learnSwipe()
        phantomSpace = false
        lastSwipe = null
        val now = SystemClock.uptimeMillis()
        val before = textBefore(2)
        // Double space types a full stop, as on Gboard and iPhone.
        if (now - lastSpaceAt < DOUBLE_SPACE_MS && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceAt = 0
            previousWord = null
        } else {
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        if (page == Page.SYMBOLS) setPage(symbolsFrom) // like Gboard: a space after a symbol goes back to letters
        updateShift()
        showNextWords()
    }

    override fun onDelete() {
        val query = search
        if (query != null) {
            if (query.isNotEmpty()) query.deleteCharAt(query.lastIndex)
            return updateSearch()
        }
        val ic = currentInputConnection ?: return
        phantomSpace = false
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.lastIndex)
            ic.setComposingText(composing, 1)
            if (composing.isEmpty()) setStrip(Strip.NONE) else showCompletions()
            return
        }
        val swiped = lastSwipe
        lastSwipe = null
        // (An empty answer means the app doesn't report text; trust that the cursor is still there.)
        if (swiped != null && textBefore(swiped.length).let { it == swiped || it.isEmpty() }) {
            ic.deleteSurroundingText(swiped.length, 0) // a swiped word goes in one press
            unlearnedSwipe = null // a wrong guess: don't learn it
        } else if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        setStrip(Strip.NONE)
        updateShift()
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray) {
        val v = view ?: return
        val dict = dictionary ?: return showMessage(getString(R.string.kb_loading_words))
        val layout = v.layout
        val index = dict.swipeIndex(QWERTY_INDEX, layout::keyOf)
        val words = GestureDecoder.decode(xs, ys, index, layout.swipeCenters(), layout.unitWidth())
        Log.i(TAG, "swipe (${xs.size} points) -> $words")
        if (words.isEmpty()) return
        val ic = currentInputConnection ?: return
        finishWord()
        learnSwipe()
        val before = textBefore(1)
        val space = phantomSpace || (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in NO_SPACE_AFTER)
        val shift = v.shift
        val word = cased(words[0], shift)
        val typed = if (space) " $word" else word
        ic.commitText(typed, 1)
        lastSwipe = word
        unlearnedSwipe = word
        swipeEnd = if (selEnd >= 0) selEnd + typed.length else -1
        phantomSpace = true
        if (shift == KeyboardView.Shift.ON) v.shift = KeyboardView.Shift.OFF
        // Strip: the other guesses on both sides of the typed word, like Gboard.
        stripWords = mutableListOf(
            words.getOrNull(1)?.let { cased(it, shift) } ?: "", word, words.getOrNull(2)?.let { cased(it, shift) } ?: "",
        )
        setStrip(Strip.SWIPE)
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val word = stripWords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        when (strip) {
            Strip.COMPLETIONS -> {
                ic.setComposingText(word, 1)
                composing.setLength(0)
                composing.append(word)
                finishWord()
                ic.commitText(" ", 1)
                updateShift()
                showNextWords()
            }
            Strip.NEXT -> {
                startWord()
                ic.commitText("$word ", 1)
                learnWord(word)
                updateShift()
                showNextWords()
            }
            Strip.SWIPE -> {
                val swiped = lastSwipe ?: return
                if (index == 1 || textBefore(swiped.length).let { it != swiped && it.isNotEmpty() }) return
                ic.deleteSurroundingText(swiped.length, 0)
                ic.commitText(word, 1)
                if (swipeEnd >= 0) swipeEnd += word.length - swiped.length
                stripWords[index] = swiped
                stripWords[1] = word
                lastSwipe = word
                unlearnedSwipe = word
                setStrip(Strip.SWIPE)
            }
            Strip.NONE -> Unit
        }
    }

    override fun onTrackpadStart() {
        finishWord()
        learnSwipe()
        phantomSpace = false
        lastSwipe = null
        previousWord = null
        setStrip(Strip.NONE)
    }

    override fun onCursor(dx: Int, dy: Int) {
        val code = when {
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dy > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> KeyEvent.KEYCODE_DPAD_UP
        }
        sendDownUpKeyEvents(code)
    }

    private fun toggleShift() {
        val v = view ?: return
        val now = SystemClock.uptimeMillis()
        v.shift = when (v.shift) {
            KeyboardView.Shift.OFF -> KeyboardView.Shift.ON
            KeyboardView.Shift.ON -> if (now - lastShiftAt < DOUBLE_TAP_MS) KeyboardView.Shift.LOCKED else KeyboardView.Shift.OFF
            KeyboardView.Shift.LOCKED -> KeyboardView.Shift.OFF
        }
        lastShiftAt = now
    }

    /** Capital at the start of a sentence, if the field asks for it. */
    private fun updateShift() {
        val v = view ?: return
        if (v.shift == KeyboardView.Shift.LOCKED || composing.isNotEmpty() || search != null) return
        if (page != Page.QWERTY) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        v.shift = if (caps != 0) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    private fun cased(word: String, shift: KeyboardView.Shift?) = when (shift) {
        KeyboardView.Shift.LOCKED -> word.uppercase()
        KeyboardView.Shift.ON -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    private fun showCompletions() {
        val dict = dictionary
        if (!predict || dict == null || composing.isEmpty()) return setStrip(Strip.NONE)
        val typed = composing.toString()
        val capitalized = typed[0].isUpperCase()
        val completions = dict.complete(typed, 4)
            .map { if (capitalized) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
            .filter { it != typed }
        // Left: what was typed; middle: the best completion; right: the next one.
        stripWords = mutableListOf(typed, completions.getOrNull(0) ?: "", completions.getOrNull(1) ?: "")
        setStrip(Strip.COMPLETIONS)
    }

    /** After a space: the words you usually type next (learned on this phone). */
    private fun showNextWords() {
        val previous = previousWord
        if (!predict || previous == null) return setStrip(Strip.NONE)
        val shift = view?.shift
        val next = learning.next(previous, 3).map { cased(it, shift) }
        if (next.isEmpty()) return setStrip(Strip.NONE)
        stripWords = mutableListOf(next.getOrElse(1) { "" }, next[0], next.getOrElse(2) { "" })
        setStrip(Strip.NEXT)
    }

    private fun setStrip(kind: Strip) {
        strip = kind
        if (kind == Strip.NONE) stripWords = mutableListOf()
        view?.suggestions = if (kind == Strip.NONE) emptyList() else stripWords.toList()
    }

    /** Ends the word being typed (keeping it as shown) and learns it. */
    private fun finishWord() {
        if (composing.isEmpty()) return
        val word = composing.toString()
        currentInputConnection?.finishComposingText()
        composing.clear()
        learnWord(word)
        setStrip(Strip.NONE)
    }

    private fun learnSwipe() {
        unlearnedSwipe?.let { learnWord(it) }
        unlearnedSwipe = null
    }

    private fun learnWord(word: String) {
        if (word.none { it.isLetter() }) return
        if (allowLearning) {
            learning.learn(word, previousWord)
            dictionary?.findOrAdd(word)?.personal = learning.score(word)
        }
        previousWord = word
    }

    private fun setPage(p: Page) {
        finishWord()
        page = p
        showPage()
        updateShift()
    }

    private fun showPage() {
        val v = view ?: return
        v.layout = when (page) {
            Page.VOICE -> Layouts.voice()
            Page.QWERTY -> Layouts.qwerty(comma)
            Page.SYMBOLS -> Layouts.symbols()
            Page.NUMBERS -> Layouts.numbers()
            Page.PHONE -> Layouts.phone()
        }
        v.qwertyShown = page == Page.QWERTY
        v.numpadShown = page == Page.NUMBERS
        if (page != Page.QWERTY) v.shift = KeyboardView.Shift.OFF
        fitPanelHost()
    }

    /** Panels cover exactly the keys area (lower under the emoji search bar). */
    private fun fitPanelHost() {
        val v = view ?: return
        val host = panelHost ?: return
        val params = host.layoutParams as FrameLayout.LayoutParams
        if (params.height != v.keysAreaHeight || params.topMargin != v.keysAreaTop) {
            params.height = v.keysAreaHeight
            params.topMargin = v.keysAreaTop
            host.layoutParams = params
        }
    }

    private fun textBefore(n: Int): String = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    private fun enterIcon(info: EditorInfo): Int {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return R.drawable.ic_enter
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_done
            else -> R.drawable.ic_enter
        }
    }

    /**
     * The system's "hide keyboard" and "switch keyboard" buttons are drawn over the keyboard's
     * background; give them dark icons on a light keyboard so they stay visible.
     */
    @Suppress("DEPRECATION")
    private fun styleNavigationBar(theme: KeyboardTheme) {
        val w = window?.window ?: return
        w.navigationBarColor = theme.background // Android 14 and older; ignored edge-to-edge
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = !theme.dark
    }

    // --- toolbar and panels -------------------------------------------------------------------

    override fun onTool(tool: KeyboardView.Tool) {
        when (tool) {
            KeyboardView.Tool.EMOJI, KeyboardView.Tool.CLIPBOARD ->
                if (view?.openTool == tool) closePanel() else openPanel(tool)
            KeyboardView.Tool.NUMPAD -> {
                closePanel()
                if (page == Page.NUMBERS) {
                    setPage(numbersFrom)
                } else {
                    numbersFrom = if (page == Page.SYMBOLS) symbolsFrom else page
                    setPage(Page.NUMBERS)
                }
            }
            KeyboardView.Tool.KEYBOARD -> {
                closePanel()
                setPage(if (page == Page.QWERTY) Page.VOICE else Page.QWERTY)
            }
            KeyboardView.Tool.UNDO -> sendShortcut(KeyEvent.KEYCODE_Z, shift = false)
            KeyboardView.Tool.REDO -> sendShortcut(KeyEvent.KEYCODE_Z, shift = true)
        }
    }

    private fun openPanel(tool: KeyboardView.Tool) {
        val host = panelHost ?: return
        val theme = theme ?: return
        finishWord()
        host.removeAllViews()
        val panel = when (tool) {
            KeyboardView.Tool.EMOJI -> EmojiPanel(this, theme, this)
            KeyboardView.Tool.CLIPBOARD -> ClipboardPanel(this, theme, this, recentClips())
            else -> return
        }
        host.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view?.openTool = tool
    }

    private fun closePanel() {
        panelHost?.removeAllViews()
        if (view?.openTool != null) view?.openTool = null
    }

    override fun onPanelText(text: String) {
        val ic = currentInputConnection ?: return
        learnSwipe()
        if (phantomSpace) ic.commitText(" ", 1)
        phantomSpace = false
        lastSwipe = null
        ic.commitText(text, 1)
    }

    override fun onPanelDelete() = onDelete()

    override fun onPanelClose() = closePanel()

    // --- emoji search ---------------------------------------------------------------------

    /** The emoji panel's search field: the keys now type a description; matches show in the strip. */
    override fun onEmojiSearch() {
        val v = view ?: return
        closePanel()
        finishWord()
        learnSwipe()
        search = StringBuilder()
        searchFrom = page
        v.searchQuery = ""
        v.swipeEnabled = false
        v.shift = KeyboardView.Shift.OFF
        setPage(Page.QWERTY)
        updateSearch()
    }

    private fun searchKey(key: Key) {
        val query = search ?: return
        when (key.type) {
            KeyType.CHAR -> query.append(key.text.first().lowercaseChar())
            KeyType.SPACE -> query.append(' ')
            KeyType.ENTER -> return onSearchClose()
            KeyType.MIC -> {
                endSearch()
                return onMic()
            }
            else -> return
        }
        updateSearch()
    }

    private fun updateSearch() {
        val query = search ?: return
        searchResults = EmojiData.get(this).search(query.toString(), EmojiData.recent(this))
        view?.searchQuery = query.toString()
        view?.searchResults = searchResults
    }

    override fun onSearchResult(index: Int) {
        val emoji = searchResults.getOrNull(index) ?: return
        EmojiData.rememberRecent(this, emoji)
        onPanelText(emoji)
    }

    /** Back to the emoji panel. */
    override fun onSearchClose() {
        endSearch()
        openPanel(KeyboardView.Tool.EMOJI)
    }

    private fun endSearch() {
        if (search == null) return
        search = null
        searchResults = emptyList()
        view?.searchQuery = null
        view?.searchResults = emptyList()
        view?.swipeEnabled = predict
        setPage(searchFrom)
    }

    override fun onClipboardAction(id: Int) {
        finishWord()
        currentInputConnection?.performContextMenuAction(id)
        if (id == android.R.id.paste || id == android.R.id.cut) closePanel()
    }

    /**
     * Undo and redo as Ctrl+Z and Ctrl+Shift+Z: Android text fields, Chrome and most apps
     * handle these shortcuts (there is no undo call for keyboards).
     */
    private fun sendShortcut(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        finishWord()
        learnSwipe()
        phantomSpace = false
        lastSwipe = null
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Called when something is copied. Keyboards may read the clipboard while they're the default. */
    private fun rememberClip() {
        val clip = try {
            clipboard?.primaryClip
        } catch (e: SecurityException) {
            null
        } ?: return
        // Apps mark passwords and other secrets as sensitive: don't keep those.
        if (clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        clips.removeAll { it.text == text }
        clips.add(0, Clip(text, SystemClock.elapsedRealtime()))
        while (clips.size > MAX_CLIPS) clips.removeAt(clips.lastIndex)
    }

    private fun recentClips(): List<String> {
        rememberClip() // in case the copy happened while the listener wasn't running
        val cutoff = SystemClock.elapsedRealtime() - CLIP_LIFETIME_MS
        clips.removeAll { it.at < cutoff }
        return clips.map { it.text }
    }

    // --- voice --------------------------------------------------------------------------------

    private fun onMic() {
        if (dictation.state != State.IDLE) return
        closePanel()
        when {
            !hasMicPermission() -> openApp(getString(R.string.kb_need_mic))
            !ModelManager.isReady(this) -> openApp(getString(R.string.kb_need_model))
            else -> {
                finishWord()
                learnSwipe()
                phantomSpace = false
                lastSwipe = null
                setStrip(Strip.NONE)
                view?.voiceStatus = getString(R.string.kb_listening)
                view?.enterVoice()
                dictation.start()
            }
        }
    }

    override fun onPillTap() {
        if (dictation.state == State.RECORDING) dictation.stop()
    }

    override fun onVoiceCancel() {
        dictation.cancel()
        view?.exitVoice()
    }

    override fun onState(state: State) {
        val v = view ?: return
        when (state) {
            State.IDLE -> v.exitVoice()
            State.RECORDING -> {
                v.voiceStatus = getString(R.string.kb_listening)
                v.setPillMode(PillRenderer.Mode.RECORDING)
            }
            State.LOADING, State.TRANSCRIBING, State.FORMATTING -> {
                v.voiceStatus = getString(
                    when (state) {
                        State.LOADING -> R.string.kb_loading
                        State.TRANSCRIBING -> R.string.kb_transcribing
                        else -> R.string.kb_formatting
                    },
                )
                v.setPillMode(PillRenderer.Mode.WORKING)
            }
        }
    }

    override fun onLevel(level: Float) {
        view?.pushLevel(level)
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val before = textBefore(1)
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && text.first() !in NO_SPACE_BEFORE
        ic.commitText(if (needsSpace) " $text" else text, 1)
        // Dictated words count too (names spelled by the clean-up become typeable).
        previousWord = null
        for (match in WORD.findAll(text)) learnWord(match.value)
        if (text.trimEnd().lastOrNull() in SENTENCE_ENDS.map { it[0] }) previousWord = null
        updateShift()
    }

    override fun onError(message: String) {
        view?.exitVoice()
        showMessage(message)
    }

    private fun showMessage(text: String) {
        view?.message = text
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 4000)
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("QueryPermissionsNeeded")
    private fun openApp(reason: String) {
        showMessage(reason)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private companion object {
        const val TAG = "WhistlefluxKeyboard"
        const val QWERTY_INDEX = "qwerty"
        const val NO_SPACE_BEFORE = ".,!?;:)]}'\"-\n"
        const val NO_SPACE_AFTER = "([{\"'/@-\n"
        val SENTENCE_ENDS = setOf(".", "!", "?")
        val WORD = Regex("[A-Za-z][A-Za-z']*")
        const val DOUBLE_SPACE_MS = 600L
        const val DOUBLE_TAP_MS = 350L
        const val MAX_CLIPS = 10
        const val CLIP_LIFETIME_MS = 60 * 60 * 1000L
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val ADDRESS_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}
