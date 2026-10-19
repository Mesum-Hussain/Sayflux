package app.whistleflux.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import app.whistleflux.engine.ModelManager
import app.whistleflux.engine.Settings
import app.whistleflux.specs.NativeWhistlefluxSpec
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.Executors

/** What the setup screen (App.tsx) can ask of the native side. See specs/NativeWhistleflux.ts. */
class WhistlefluxModule(private val context: ReactApplicationContext) : NativeWhistlefluxSpec(context) {
    private val worker = Executors.newSingleThreadExecutor()

    override fun initialize() {
        super.initialize()
        worker.execute { ModelManager.deleteOldDownloads(context) }
    }

    override fun invalidate() {
        worker.shutdown()
        super.invalidate()
    }

    override fun getStatus(promise: Promise) {
        worker.execute {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val pkg = context.packageName
            val current = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
            promise.resolve(
                Arguments.createMap().apply {
                    putBoolean(
                        "micGranted",
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    )
                    putBoolean("keyboardEnabled", imm.enabledInputMethodList.any { it.packageName == pkg })
                    putBoolean("keyboardSelected", current?.startsWith("$pkg/") == true)
                },
            )
        }
    }

    override fun getSettings(): WritableMap {
        val s = Settings.load(context)
        return Arguments.createMap().apply {
            putBoolean("basicCleanup", s.basicCleanup)
            putBoolean("smartCleanup", s.smartCleanup)
            putString("apiKey", s.apiKey)
            putString("model", s.model)
            putString("instructions", s.instructions)
        }
    }

    override fun getModels(): WritableArray =
        Arguments.createArray().apply {
            Settings.MODELS.forEach { m ->
                pushMap(
                    Arguments.createMap().apply {
                        putString("id", m.id)
                        putString("label", m.label)
                        putString("note", m.note)
                    },
                )
            }
        }

    override fun saveSettings(settings: ReadableMap) {
        Settings.save(
            context,
            Settings(
                basicCleanup = settings.getBoolean("basicCleanup"),
                smartCleanup = settings.getBoolean("smartCleanup"),
                apiKey = settings.getString("apiKey").orEmpty().trim(),
                model = settings.getString("model").orEmpty().takeIf { id -> Settings.MODELS.any { it.id == id } }
                    ?: Settings.DEFAULT_MODEL,
                instructions = settings.getString("instructions").orEmpty(),
            ),
        )
    }

    override fun openKeyboardSettings() {
        val intent = Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun showKeyboardPicker() {
        // The picker only opens for the app in front, so run it on the UI thread.
        UiThreadUtil.runOnUiThread {
            context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }
}
