package app.whistleflux.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt

/**
 * Material 3 colors for the keyboard, like Gboard: on Android 12+ they follow the
 * wallpaper (Material You's system palettes); before that, Material's baseline purple.
 * Light or dark follows the system.
 */
class KeyboardTheme(context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Tonal palettes: tone (0..100) -> color.
    private val neutral: (Int) -> Int
    private val neutralVariant: (Int) -> Int
    private val primary: (Int) -> Int
    private val secondary: (Int) -> Int

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neutral = system(context, NEUTRAL1)
            neutralVariant = system(context, NEUTRAL2)
            primary = system(context, ACCENT1)
            secondary = system(context, ACCENT2)
        } else {
            neutral = baseline(BASELINE_NEUTRAL)
            neutralVariant = baseline(BASELINE_NEUTRAL_VARIANT)
            primary = baseline(BASELINE_PRIMARY)
            secondary = baseline(BASELINE_SECONDARY)
        }
    }

    private fun tone(light: Int, dark: Int) = if (this.dark) dark else light

    @ColorInt val background = neutral(tone(90, 10))
    @ColorInt val key = neutral(tone(99, 25))
    @ColorInt val keyPressed = neutral(tone(80, 40))
    @ColorInt val functionKey = secondary(tone(85, 30))
    @ColorInt val functionKeyPressed = secondary(tone(70, 45))
    @ColorInt val enterKey = primary(tone(40, 80))
    @ColorInt val onEnterKey = primary(tone(100, 20))
    @ColorInt val label = neutral(tone(10, 95))
    @ColorInt val functionLabel = secondary(tone(10, 90))
    @ColorInt val muted = neutralVariant(tone(40, 75))
    @ColorInt val divider = neutralVariant(tone(80, 30))
    @ColorInt val preview = neutral(tone(99, 30))
    @ColorInt val trail = primary(tone(40, 80))
    @ColorInt val accent = primary(tone(40, 80))

    // The mic key and the voice pill it grows into. The pill keeps the desktop app's look
    // (dark pill, light bars, red dot) using Material's inverse surface colors.
    @ColorInt val micKey = primary(tone(90, 30))
    @ColorInt val onMicKey = primary(tone(10, 90))
    @ColorInt val pill = neutral(tone(20, 90))
    @ColorInt val pillBar = neutral(tone(95, 20))
    @ColorInt val pillWorking = primary(tone(80, 40))
    @ColorInt val pillDot = "#F50A0A".toColorInt() // the logo's red

    @ColorInt fun pressedOver(@ColorInt base: Int): Int = ColorUtils.blendARGB(base, label, 0.12f)

    // The system palette colors exist from Android 12; they are only read there (see init).
    @SuppressLint("InlinedApi")
    private companion object {
        // Material You system palettes: tone -> android.R.color resource.
        val NEUTRAL1 = mapOf(
            0 to android.R.color.system_neutral1_1000, 10 to android.R.color.system_neutral1_900,
            20 to android.R.color.system_neutral1_800, 30 to android.R.color.system_neutral1_700,
            40 to android.R.color.system_neutral1_600, 50 to android.R.color.system_neutral1_500,
            60 to android.R.color.system_neutral1_400, 70 to android.R.color.system_neutral1_300,
            80 to android.R.color.system_neutral1_200, 90 to android.R.color.system_neutral1_100,
            95 to android.R.color.system_neutral1_50, 99 to android.R.color.system_neutral1_10,
            100 to android.R.color.system_neutral1_0,
        )
        val NEUTRAL2 = mapOf(
            0 to android.R.color.system_neutral2_1000, 10 to android.R.color.system_neutral2_900,
            20 to android.R.color.system_neutral2_800, 30 to android.R.color.system_neutral2_700,
            40 to android.R.color.system_neutral2_600, 50 to android.R.color.system_neutral2_500,
            60 to android.R.color.system_neutral2_400, 70 to android.R.color.system_neutral2_300,
            80 to android.R.color.system_neutral2_200, 90 to android.R.color.system_neutral2_100,
            95 to android.R.color.system_neutral2_50, 99 to android.R.color.system_neutral2_10,
            100 to android.R.color.system_neutral2_0,
        )
        val ACCENT1 = mapOf(
            0 to android.R.color.system_accent1_1000, 10 to android.R.color.system_accent1_900,
            20 to android.R.color.system_accent1_800, 30 to android.R.color.system_accent1_700,
            40 to android.R.color.system_accent1_600, 50 to android.R.color.system_accent1_500,
            60 to android.R.color.system_accent1_400, 70 to android.R.color.system_accent1_300,
            80 to android.R.color.system_accent1_200, 90 to android.R.color.system_accent1_100,
            95 to android.R.color.system_accent1_50, 99 to android.R.color.system_accent1_10,
            100 to android.R.color.system_accent1_0,
        )
        val ACCENT2 = mapOf(
            0 to android.R.color.system_accent2_1000, 10 to android.R.color.system_accent2_900,
            20 to android.R.color.system_accent2_800, 30 to android.R.color.system_accent2_700,
            40 to android.R.color.system_accent2_600, 50 to android.R.color.system_accent2_500,
            60 to android.R.color.system_accent2_400, 70 to android.R.color.system_accent2_300,
            80 to android.R.color.system_accent2_200, 90 to android.R.color.system_accent2_100,
            95 to android.R.color.system_accent2_50, 99 to android.R.color.system_accent2_10,
            100 to android.R.color.system_accent2_0,
        )

        // Material 3 baseline (purple) tonal palettes, for Android 11 and older.
        val BASELINE_NEUTRAL = mapOf(
            0 to 0xFF000000, 10 to 0xFF1D1B20, 20 to 0xFF322F35, 30 to 0xFF48464C, 40 to 0xFF605D64,
            50 to 0xFF79767D, 60 to 0xFF938F96, 70 to 0xFFAEA9B1, 80 to 0xFFCAC5CD, 90 to 0xFFE6E0E9,
            95 to 0xFFF5EFF7, 99 to 0xFFFFFBFF, 100 to 0xFFFFFFFF,
        )
        val BASELINE_NEUTRAL_VARIANT = mapOf(
            0 to 0xFF000000, 10 to 0xFF1D1A22, 20 to 0xFF322F37, 30 to 0xFF49454F, 40 to 0xFF605D66,
            50 to 0xFF79747E, 60 to 0xFF938F99, 70 to 0xFFAEA9B4, 80 to 0xFFCAC4D0, 90 to 0xFFE7E0EC,
            95 to 0xFFF5EEFA, 99 to 0xFFFFFBFE, 100 to 0xFFFFFFFF,
        )
        val BASELINE_PRIMARY = mapOf(
            0 to 0xFF000000, 10 to 0xFF21005D, 20 to 0xFF381E72, 30 to 0xFF4F378B, 40 to 0xFF6750A4,
            50 to 0xFF7F67BE, 60 to 0xFF9A82DB, 70 to 0xFFB69DF8, 80 to 0xFFD0BCFF, 90 to 0xFFEADDFF,
            95 to 0xFFF6EDFF, 99 to 0xFFFFFBFE, 100 to 0xFFFFFFFF,
        )
        val BASELINE_SECONDARY = mapOf(
            0 to 0xFF000000, 10 to 0xFF1D192B, 20 to 0xFF332D41, 30 to 0xFF4A4458, 40 to 0xFF625B71,
            50 to 0xFF7A7289, 60 to 0xFF958DA5, 70 to 0xFFB0A7C0, 80 to 0xFFCCC2DC, 90 to 0xFFE8DEF8,
            95 to 0xFFF6EDFF, 99 to 0xFFFFFBFE, 100 to 0xFFFFFFFF,
        )

        /** Tones between the stored ones (25, 45, 75, 85) are blended from their neighbors. */
        fun interpolated(lookup: (Int) -> Int, tone: Int): Int {
            val stops = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)
            if (tone in stops) return lookup(tone)
            val hi = stops.first { it > tone }
            val lo = stops.last { it < tone }
            return ColorUtils.blendARGB(lookup(lo), lookup(hi), (tone - lo).toFloat() / (hi - lo))
        }

        fun system(context: Context, palette: Map<Int, Int>): (Int) -> Int =
            { tone -> interpolated({ context.getColor(palette.getValue(it)) }, tone) }

        fun baseline(palette: Map<Int, Long>): (Int) -> Int =
            { tone -> interpolated({ palette.getValue(it).toInt() }, tone) }
    }
}
