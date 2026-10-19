package app.whistleflux.engine

import android.content.Context
import java.io.File

/**
 * The speech model ships inside the app: Cactus Whistle (whistle.cact, 17 MB). The build
 * puts it in assets/models/ (see android/app/build.gradle). Same model as the desktop
 * app (src/whistleflux/speech.py).
 */
object ModelManager {
    const val ASSET = "models/whistle.cact"

    fun isReady(context: Context): Boolean = "whistle.cact" in context.assets.list("models").orEmpty()

    /** Earlier versions downloaded a model into app storage; free that space. */
    fun deleteOldDownloads(context: Context) {
        File(context.filesDir, "models").takeIf { it.exists() }?.deleteRecursively()
    }
}
