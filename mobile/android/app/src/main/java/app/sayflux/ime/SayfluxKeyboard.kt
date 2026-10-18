package app.sayflux.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.sayflux.R
import app.sayflux.engine.Dictation
import app.sayflux.engine.Dictation.State
import app.sayflux.engine.ModelManager
import app.sayflux.engine.SpeechEngine

/**
 * The Sayflux keyboard: a big mic button plus space, delete, enter and a key to switch
 * back to your usual keyboard. Tap the mic to start, tap again to stop; the text is
 * transcribed on the phone, cleaned up by Gemini if set up, and typed at the cursor.
 */
class SayfluxKeyboard : InputMethodService(), Dictation.Listener {
    private lateinit var dictation: Dictation
    private val main = Handler(Looper.getMainLooper())

    private var status: TextView? = null
    private var mic: View? = null
    private var micIcon: ImageView? = null
    private var busy: ProgressBar? = null
    private var ring: View? = null
    private var cancel: View? = null

    private var message: String? = null
    private val clearMessage = Runnable { message = null; render() }
    private var level = 0f

    override fun onCreate() {
        super.onCreate()
        dictation = Dictation(this, this)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard, null)
        status = root.findViewById(R.id.kb_status)
        mic = root.findViewById(R.id.kb_mic)
        micIcon = root.findViewById(R.id.kb_mic_icon)
        busy = root.findViewById(R.id.kb_busy)
        ring = root.findViewById(R.id.kb_ring)
        cancel = root.findViewById(R.id.kb_cancel)

        mic?.setOnClickListener { haptic(it); onMicTap() }
        cancel?.setOnClickListener { haptic(it); dictation.cancel() }
        root.findViewById<View>(R.id.kb_space).setOnClickListener {
            haptic(it)
            currentInputConnection?.commitText(" ", 1)
        }
        root.findViewById<View>(R.id.kb_enter).setOnClickListener {
            haptic(it)
            sendKeyChar('\n') // runs the field's action (send, search...) or inserts a new line
        }
        root.findViewById<View>(R.id.kb_switch).apply {
            setOnClickListener { haptic(it); switchKeyboard() }
            setOnLongClickListener { showPicker(); true }
        }
        setUpBackspace(root.findViewById(R.id.kb_backspace))

        // Keep the keys above the navigation bar on edge-to-edge devices.
        val basePadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + nav)
            insets
        }
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (hasMicPermission()) dictation.warmUp()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation.cancel() // keyboard hidden or field changed: drop what was being said
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        dictation.destroy()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The speech model uses a lot of memory: give it back when Android is short on it.
        if (level >= TRIM_MEMORY_BACKGROUND && !isInputViewShown && dictation.state == State.IDLE) {
            SpeechEngine.release()
        }
    }

    private fun onMicTap() {
        when (dictation.state) {
            State.IDLE -> {
                when {
                    !hasMicPermission() -> openApp(getString(R.string.kb_need_mic))
                    !ModelManager.isReady(this) -> openApp(getString(R.string.kb_need_model))
                    else -> dictation.start()
                }
            }
            State.RECORDING -> dictation.stop()
            else -> Unit // still working on the last dictation
        }
    }

    // Dictation.Listener

    override fun onState(state: State) {
        if (state == State.RECORDING) {
            level = 0f
            message = null
        }
        render()
    }

    override fun onLevel(level: Float) {
        this.level = this.level * 0.6f + level * 0.4f
        ring?.apply {
            val scale = 1f + this@SayfluxKeyboard.level * 0.45f
            scaleX = scale
            scaleY = scale
            alpha = 0.18f + this@SayfluxKeyboard.level * 0.3f
        }
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && text.first() !in NO_SPACE_BEFORE
        ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    override fun onError(message: String) {
        showMessage(message)
    }

    // UI

    private fun render() {
        val state = dictation.state
        status?.text = when (state) {
            State.IDLE -> message ?: getString(R.string.kb_tap_to_speak)
            State.LOADING -> getString(R.string.kb_loading)
            State.RECORDING -> getString(R.string.kb_listening)
            State.TRANSCRIBING -> getString(R.string.kb_transcribing)
            State.FORMATTING -> getString(R.string.kb_formatting)
        }
        val recording = state == State.RECORDING
        val working = state == State.LOADING || state == State.TRANSCRIBING || state == State.FORMATTING
        val color = getColor(if (recording) R.color.kb_recording else R.color.kb_accent)
        mic?.backgroundTintList = ColorStateList.valueOf(color)
        mic?.alpha = if (working) 0.6f else 1f
        micIcon?.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        micIcon?.visibility = if (working) View.INVISIBLE else View.VISIBLE
        busy?.visibility = if (working) View.VISIBLE else View.GONE
        cancel?.visibility = if (recording || working) View.VISIBLE else View.INVISIBLE
        if (!recording) ring?.animate()?.alpha(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
    }

    private fun showMessage(text: String) {
        message = text
        render()
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 4000)
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpBackspace(key: View) {
        // Hold to keep deleting, like a normal keyboard.
        val repeat = object : Runnable {
            override fun run() {
                deleteOne()
                main.postDelayed(this, 50)
            }
        }
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    deleteOne()
                    main.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun switchKeyboard() {
        dictation.cancel()
        val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()
        if (!switched) showPicker()
    }

    private fun showPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openApp(reason: String) {
        showMessage(reason)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    companion object {
        private const val NO_SPACE_BEFORE = ".,!?;:)]}'\"-\n"
    }
}
