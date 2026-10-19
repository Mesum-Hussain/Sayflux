package app.whistleflux.ime

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowCompat
import app.whistleflux.R
import app.whistleflux.engine.Dictation
import app.whistleflux.engine.Dictation.State
import app.whistleflux.engine.ModelManager
import java.util.concurrent.Executors

/**
 * The Whistleflux keyboard: a Gboard-style typing keyboard (tap or swipe typing, word
 * suggestions, a space bar that turns the keyboard into a trackpad) with a mic key that
 * grows into the voice pill. Speech is transcribed on the phone, cleaned up (rules, then
 * Gemini if set up) and typed at the cursor. Drawing and touches are in [KeyboardView];
 * this class does the typing.
 */
class WhistlefluxKeyboard : InputMethodService(), Dictation.Listener, KeyboardView.Listener {
    private lateinit var dictation: Dictation
    private val main = Handler(Looper.getMainLooper())
    private var view: KeyboardView? = null

    @Volatile private var dictionary: Dictionary? = null
    private var decoder: GestureDecoder? = null

    private enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS }
    private enum class Strip { NONE, TYPING, SWIPE }

    private var page = Page.LETTERS
    private var comma = ","
    private var phone = false
    /** Suggestions, swipe typing and the word-in-progress underline; off in password fields. */
    private var predict = true

    private val composing = StringBuilder()
    private var strip = Strip.NONE
    private var stripWords: MutableList<String> = mutableListOf()

    /** After a swiped word, the next word gets a space in front (Gboard's "phantom space"). */
    private var phantomSpace = false
    /** The swiped word just typed: delete removes it whole, and suggestions can replace it. */
    private var lastSwipe: String? = null

    private var lastSpaceAt = 0L
    private var lastShiftAt = 0L
    private val clearMessage = Runnable { view?.message = null }

    override fun onCreate() {
        super.onCreate()
        dictation = Dictation(this, this)
        // ~120,000 words: load once, off the main thread.
        Executors.newSingleThreadExecutor().execute {
            try {
                val d = Dictionary.load(this)
                main.post {
                    dictionary = d
                    decoder = GestureDecoder(d)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load the word list", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        val theme = KeyboardTheme(this)
        return KeyboardView(this, theme).also {
            it.listener = this
            view = it
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = view ?: return
        val theme = KeyboardTheme(this)
        v.applyTheme(theme)
        styleNavigationBar(theme)

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        phone = inputClass == InputType.TYPE_CLASS_PHONE
        page = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Page.NUMBERS
            else -> Page.LETTERS
        }
        val password = inputClass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS ||
            inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        predict = !password && page == Page.LETTERS
        comma = when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
            InputType.TYPE_TEXT_VARIATION_URI -> "/"
            else -> ","
        }
        v.swipeEnabled = predict
        v.enterIcon = enterIcon(info)
        composing.clear()
        phantomSpace = false
        lastSwipe = null
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
        finishComposing()
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
            setStrip(Strip.NONE)
        }
        if (composing.isEmpty()) {
            val swiped = lastSwipe
            if (swiped != null && textBefore(swiped.length) != swiped) {
                lastSwipe = null
                phantomSpace = false
                if (strip == Strip.SWIPE) setStrip(Strip.NONE)
            }
            updateShift()
        }
    }

    override fun onDestroy() {
        dictation.destroy()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- KeyboardView.Listener: typing ----------------------------------------------------

    override fun onKey(key: Key) {
        when (key.type) {
            KeyType.CHAR -> typeText(if (key.isLetter && view?.shift != KeyboardView.Shift.OFF) key.text.uppercase() else key.text)
            KeyType.SHIFT -> toggleShift()
            KeyType.SPACE -> typeSpace()
            KeyType.ENTER -> {
                finishComposing()
                phantomSpace = false
                lastSwipe = null
                sendKeyChar('\n') // runs the field's action (send, search...) or types a new line
                updateShift()
            }
            KeyType.SYMBOLS -> setPage(Page.SYMBOLS)
            KeyType.LETTERS -> setPage(Page.LETTERS)
            KeyType.MORE_SYMBOLS -> setPage(Page.MORE_SYMBOLS)
            KeyType.DELETE -> onDelete()
        }
    }

    override fun onLongPressText(text: String) = typeText(text)

    private fun typeText(text: String) {
        val ic = currentInputConnection ?: return
        val wordChar = text.length == 1 && (text[0].isLetter() || (text == "'" && composing.isNotEmpty()))
        if (predict && page == Page.LETTERS && wordChar) {
            if (phantomSpace && composing.isEmpty()) ic.commitText(" ", 1)
            phantomSpace = false
            lastSwipe = null
            composing.append(text)
            ic.setComposingText(composing, 1)
            if (view?.shift == KeyboardView.Shift.ON) view?.shift = KeyboardView.Shift.OFF
            showCompletions()
        } else {
            finishComposing()
            phantomSpace = false // punctuation sticks to a swiped word
            lastSwipe = null
            ic.commitText(text, 1)
            if (view?.shift == KeyboardView.Shift.ON && text[0].isLetter()) view?.shift = KeyboardView.Shift.OFF
            updateShift()
        }
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        finishComposing()
        phantomSpace = false
        lastSwipe = null
        val now = SystemClock.uptimeMillis()
        val before = textBefore(2)
        // Double space types a full stop, as on Gboard and iPhone.
        if (now - lastSpaceAt < DOUBLE_SPACE_MS && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceAt = 0
        } else {
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        if (page == Page.SYMBOLS || page == Page.MORE_SYMBOLS) setPage(Page.LETTERS)
        setStrip(Strip.NONE)
        updateShift()
    }

    override fun onDelete() {
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
        if (swiped != null && textBefore(swiped.length) == swiped) {
            ic.deleteSurroundingText(swiped.length, 0) // a swiped word goes in one press
            setStrip(Strip.NONE)
        } else if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        updateShift()
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray) {
        val v = view ?: return
        val decoder = decoder ?: return showMessage(getString(R.string.kb_loading_words))
        val words = decoder.decode(xs, ys, v.layout.letterCenters(), v.layout.unitWidth())
        if (words.isEmpty()) return
        val ic = currentInputConnection ?: return
        finishComposing()
        val before = textBefore(1)
        val space = phantomSpace || (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in NO_SPACE_AFTER)
        val word = cased(words[0])
        ic.commitText(if (space) " $word" else word, 1)
        lastSwipe = word
        phantomSpace = true
        if (v.shift == KeyboardView.Shift.ON) v.shift = KeyboardView.Shift.OFF
        // Strip: the other guesses on both sides of the typed word, like Gboard.
        stripWords = mutableListOf(words.getOrNull(1)?.let(::cased) ?: "", word, words.getOrNull(2)?.let(::cased) ?: "")
        setStrip(Strip.SWIPE)
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val word = stripWords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        when (strip) {
            Strip.TYPING -> {
                ic.setComposingText(word, 1)
                ic.finishComposingText()
                composing.clear()
                ic.commitText(" ", 1)
                setStrip(Strip.NONE)
                updateShift()
            }
            Strip.SWIPE -> {
                val swiped = lastSwipe ?: return
                if (index == 1 || textBefore(swiped.length) != swiped) return
                ic.deleteSurroundingText(swiped.length, 0)
                ic.commitText(word, 1)
                stripWords[index] = swiped
                stripWords[1] = word
                lastSwipe = word
                setStrip(Strip.SWIPE)
            }
            Strip.NONE -> Unit
        }
    }

    override fun onTrackpadStart() {
        finishComposing()
        phantomSpace = false
        lastSwipe = null
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
        if (v.shift == KeyboardView.Shift.LOCKED || page != Page.LETTERS || composing.isNotEmpty()) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        v.shift = if (caps != 0) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    private fun cased(word: String) = when (view?.shift) {
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
        setStrip(Strip.TYPING)
    }

    private fun setStrip(kind: Strip) {
        strip = kind
        if (kind == Strip.NONE) stripWords = mutableListOf()
        view?.suggestions = if (kind == Strip.NONE) emptyList() else stripWords.toList()
    }

    private fun finishComposing() {
        if (composing.isEmpty()) return
        currentInputConnection?.finishComposingText()
        composing.clear()
        setStrip(Strip.NONE)
    }

    private fun setPage(p: Page) {
        page = p
        showPage()
        updateShift()
    }

    private fun showPage() {
        view?.layout = when (page) {
            Page.LETTERS -> Layouts.letters(comma)
            Page.SYMBOLS -> Layouts.symbols(comma)
            Page.MORE_SYMBOLS -> Layouts.moreSymbols()
            Page.NUMBERS -> Layouts.numbers(phone)
        }
        if (page != Page.LETTERS) view?.shift = KeyboardView.Shift.OFF
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

    // --- voice --------------------------------------------------------------------------------

    override fun onMic() {
        if (dictation.state != State.IDLE) return
        when {
            !hasMicPermission() -> openApp(getString(R.string.kb_need_mic))
            !ModelManager.isReady(this) -> openApp(getString(R.string.kb_need_model))
            else -> {
                finishComposing()
                phantomSpace = false
                lastSwipe = null
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
        const val NO_SPACE_BEFORE = ".,!?;:)]}'\"-\n"
        const val NO_SPACE_AFTER = "([{\"'/@-\n"
        const val DOUBLE_SPACE_MS = 600L
        const val DOUBLE_TAP_MS = 350L
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}
