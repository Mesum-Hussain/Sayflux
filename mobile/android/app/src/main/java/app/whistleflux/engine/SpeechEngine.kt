package app.whistleflux.engine

import android.content.Context
import org.json.JSONObject

/**
 * Local speech-to-text with Cactus Whistle, run by Cactus's Needle engine through a small
 * JNI bridge (src/main/cpp/speech_jni.c). The model is 17 MB and takes well under a
 * second to load, so it is loaded once and kept: the engine has no way to unload it.
 */
object SpeechEngine {
    const val SAMPLE_RATE = 16_000

    init {
        System.loadLibrary("whistleflux_speech")
    }

    @JvmStatic private external fun nativeLoad(model: ByteArray)

    @JvmStatic private external fun nativeTranscribe(samples: FloatArray, language: String?, keywords: String?): ByteArray

    private var loaded = false

    @Synchronized
    fun isLoaded() = loaded

    /** Loads the model if needed. Call from a background thread. */
    @Synchronized
    fun preload(context: Context) {
        if (loaded) return
        check(ModelManager.isReady(context)) { "The speech model is missing from the app" }
        nativeLoad(context.assets.open(ModelManager.ASSET).use { it.readBytes() })
        loaded = true
    }

    /**
     * samples: mono floats in [-1, 1] at 16 kHz. language: "en", "de", "fr", "es", "it",
     * "nl" or "pl", or null to detect it. Call from a background thread.
     */
    @Synchronized
    fun transcribe(context: Context, samples: FloatArray, language: String? = null): String {
        if (samples.isEmpty()) return ""
        preload(context)
        val reply = nativeTranscribe(samples, language, null).toString(Charsets.UTF_8)
        return JSONObject(reply).optString("text").trim()
    }
}
