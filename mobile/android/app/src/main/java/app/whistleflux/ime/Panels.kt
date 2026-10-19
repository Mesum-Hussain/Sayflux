package app.whistleflux.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.whistleflux.R

/** Panels that replace the keys: emoji and clipboard. They sit under the suggestion strip. */
interface PanelListener {
    fun onPanelText(text: String)
    fun onPanelDelete()
    fun onPanelClose()
    /** android.R.id.selectAll, cut, copy or paste. */
    fun onClipboardAction(id: Int)
    /** The emoji search field was tapped. */
    fun onEmojiSearch()
}

private fun View.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

private fun rounded(color: Int, radius: Float, pressed: Int): Drawable {
    val shape = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(0xFFFFFFFF.toInt())
    }
    return RippleDrawable(ColorStateList.valueOf(pressed), shape, mask)
}

/** A bottom bar shared by the panels: ABC on the left, [middle] in between, delete on the right. */
@SuppressLint("ViewConstructor")
private class PanelBar(context: Context, theme: KeyboardTheme, listener: PanelListener, middle: View?) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(4f).toInt()
        setPadding(pad, pad, pad, pad)
        addView(
            TextView(context).apply {
                text = context.getString(R.string.kb_abc)
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(theme.functionLabel)
                gravity = Gravity.CENTER
                background = rounded(theme.functionKey, dp(8f), theme.pressedOver(theme.functionKey))
                contentDescription = context.getString(R.string.kb_back_to_keys)
                setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); listener.onPanelClose() }
            },
            LayoutParams(dp(64f).toInt(), LayoutParams.MATCH_PARENT),
        )
        if (middle != null) addView(middle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        else addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_backspace)!!.mutate().apply { setTint(theme.functionLabel) })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
                background = rounded(theme.functionKey, dp(8f), theme.pressedOver(theme.functionKey))
                contentDescription = context.getString(R.string.kb_delete)
                setUpRepeat(this, listener)
            },
            LayoutParams(dp(64f).toInt(), LayoutParams.MATCH_PARENT),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpRepeat(view: View, listener: PanelListener) {
        val repeat = object : Runnable {
            override fun run() {
                listener.onPanelDelete()
                handler.postDelayed(this, 50)
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onPanelDelete()
                    handler.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}

/**
 * Emoji: a search field (tap it to type a description on the keys) over one long scrolling
 * list, recently used first. Close it with the toolbar's emoji button.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, theme: KeyboardTheme, listener: PanelListener) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        val data = EmojiData.get(context)
        val recent = EmojiData.recent(context)
        addView(
            TextView(context).apply {
                text = context.getString(R.string.kb_search_emoji)
                textSize = 15f
                setTextColor(theme.muted)
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = dp(10f).toInt()
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ContextCompat.getDrawable(context, R.drawable.ic_search)!!.mutate().apply { setTint(theme.muted) },
                    null, null, null,
                )
                setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
                background = rounded(theme.key, dp(20f), theme.pressedOver(theme.key))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onEmojiSearch()
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40f).toInt()).apply {
                setMargins(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(2f).toInt())
            },
        )
        val sections = listOf(context.getString(R.string.kb_recent_emoji) to recent) + data.groups
        addView(
            EmojiGrid(context, theme, sections) { emoji ->
                EmojiData.rememberRecent(context, emoji)
                listener.onPanelText(emoji)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
}

/**
 * Clipboard: Select all / Cut / Copy / Paste for the text field, and text copied in the
 * last hour (kept in memory only, like Gboard) to paste with one tap.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val listener: PanelListener,
    clips: List<String>,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        val pad = dp(8f).toInt()

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        for ((label, id) in listOf(
            R.string.kb_select_all to android.R.id.selectAll,
            R.string.kb_cut to android.R.id.cut,
            R.string.kb_copy to android.R.id.copy,
            R.string.kb_paste to android.R.id.paste,
        )) {
            actions.addView(
                TextView(context).apply {
                    text = context.getString(label)
                    textSize = 14f
                    setTextColor(theme.functionLabel)
                    gravity = Gravity.CENTER
                    setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
                    background = rounded(theme.functionKey, dp(18f), theme.pressedOver(theme.functionKey))
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener.onClipboardAction(id)
                    }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
            )
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        if (clips.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = context.getString(R.string.kb_clipboard_empty)
                    textSize = 14f
                    setTextColor(theme.muted)
                    gravity = Gravity.CENTER
                    setPadding(pad, dp(24f).toInt(), pad, 0)
                },
            )
        }
        for (clip in clips) {
            list.addView(
                TextView(context).apply {
                    text = clip
                    textSize = 15f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(theme.label)
                    setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
                    background = rounded(theme.key, dp(10f), theme.pressedOver(theme.key))
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener.onPanelText(clip)
                    }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f).toInt() },
            )
        }
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(PanelBar(context, theme, listener, null), LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt()))
    }
}
