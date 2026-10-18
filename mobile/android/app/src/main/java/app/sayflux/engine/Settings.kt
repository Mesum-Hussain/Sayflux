package app.sayflux.engine

import android.content.Context
import androidx.core.content.edit

/** User settings, shared by the setup app and the keyboard (same process, same prefs file). */
data class Settings(
    /** Rule-based clean-up on the phone: fillers, stutters, "scratch that", "new line". */
    val basicCleanup: Boolean = true,
    /** Smart clean-up with the Gemini API (needs [apiKey]). */
    val smartCleanup: Boolean = true,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** Extra rules for the smart clean-up, e.g. names it should spell correctly. */
    val instructions: String = "",
) {
    companion object {
        // Same default as the desktop app's config (src/sayflux/config.py).
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

        private const val PREFS = "sayflux"

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Settings(
                basicCleanup = p.getBoolean("basicCleanup", true),
                smartCleanup = p.getBoolean("smartCleanup", true),
                apiKey = p.getString("apiKey", "") ?: "",
                model = p.getString("model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
                instructions = p.getString("instructions", "") ?: "",
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean("basicCleanup", s.basicCleanup)
                putBoolean("smartCleanup", s.smartCleanup)
                putString("apiKey", s.apiKey)
                putString("model", s.model)
                putString("instructions", s.instructions)
                remove("formatEnabled") // replaced by smartCleanup
            }
        }
    }
}
