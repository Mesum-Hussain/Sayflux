package app.sayflux.engine

import android.content.Context
import java.io.File

/**
 * The speech model ships inside the app: Moondream's Parakeet Redux (NVIDIA Parakeet
 * TDT 0.6B v3 with every encoder weight -1, 0 or +1), packaged for sherpa-onnx. The build
 * puts it in assets/models/<NAME>/ (see android/app/build.gradle). Same model as the
 * desktop app's default (src/sayflux/speech.py).
 */
object ModelManager {
    const val NAME = "sherpa-onnx-nemo-parakeet-redux"

    const val ENCODER = "encoder.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.onnx"
    const val TOKENS = "tokens.txt"

    /** Path of a model file inside the app's assets. */
    fun asset(name: String) = "models/$NAME/$name"

    fun isReady(context: Context): Boolean {
        val files = context.assets.list("models/$NAME").orEmpty().toSet()
        return listOf(ENCODER, DECODER, JOINER, TOKENS).all { it in files }
    }

    /** Earlier versions downloaded the model into app storage; free that space. */
    fun deleteOldDownloads(context: Context) {
        File(context.filesDir, "models").takeIf { it.exists() }?.deleteRecursively()
    }
}
