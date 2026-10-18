package app.sayflux.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * Local speech-to-text with Parakeet Redux via sherpa-onnx. The model takes a few seconds and
 * several hundred MB of memory to load, so it is loaded once (ideally when the keyboard
 * opens) and kept until Android asks for memory back.
 */
object SpeechEngine {
    const val SAMPLE_RATE = 16_000

    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun isLoaded() = recognizer != null

    @Synchronized
    private fun get(context: Context): OfflineRecognizer {
        recognizer?.let { return it }
        check(ModelManager.isReady(context)) { "The speech model is missing from the app" }
        fun path(name: String) = ModelManager.asset(name)
        val cores = Runtime.getRuntime().availableProcessors()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(ModelManager.ENCODER),
                    decoder = path(ModelManager.DECODER),
                    joiner = path(ModelManager.JOINER),
                ),
                tokens = path(ModelManager.TOKENS),
                modelType = "nemo_transducer",
                numThreads = (cores / 2).coerceIn(1, 4),
            ),
            decodingMethod = "greedy_search",
        )
        // Reads the model straight from the app's assets.
        return OfflineRecognizer(assetManager = context.assets, config = config).also { recognizer = it }
    }

    /** Loads the model if needed. Call from a background thread. */
    fun preload(context: Context) {
        get(context)
    }

    /** samples: mono floats in [-1, 1] at 16 kHz. Call from a background thread. */
    @Synchronized
    fun transcribe(context: Context, samples: FloatArray): String {
        val r = get(context)
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
    }
}
