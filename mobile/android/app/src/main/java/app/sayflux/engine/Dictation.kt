package app.sayflux.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * One dictation at a time: record and transcribe on the phone, clean up with simple
 * rules, then with the Gemini API if a key is set (only the text is sent).
 * Listener callbacks always arrive on the main thread.
 */
class Dictation(context: Context, private val listener: Listener) {
    enum class State { IDLE, LOADING, RECORDING, TRANSCRIBING, FORMATTING }

    interface Listener {
        fun onState(state: State)
        fun onLevel(level: Float)
        fun onText(text: String)
        fun onError(message: String)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sayflux-dictation") }
    private var recorder: Recorder? = null
    private var generation = 0 // bumps on cancel so stale results are dropped

    var state = State.IDLE
        private set

    private fun setState(s: State) {
        state = s
        listener.onState(s)
    }

    /** Loads the speech model in the background so the first dictation is fast. */
    fun warmUp() {
        if (SpeechEngine.isLoaded() || !ModelManager.isReady(app)) return
        worker.execute {
            try {
                SpeechEngine.preload(app)
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't load the speech model", e)
            }
        }
    }

    fun start() {
        if (state != State.IDLE) return
        val r = Recorder { level -> main.post { if (state == State.RECORDING) listener.onLevel(level) } }
        try {
            r.start()
        } catch (e: Exception) {
            listener.onError(e.message ?: "Couldn't start the microphone")
            return
        }
        recorder = r
        setState(State.RECORDING)
        warmUp()
    }

    fun stop() {
        if (state != State.RECORDING) return
        val audio = recorder?.stop() ?: FloatArray(0)
        recorder = null
        val gen = generation
        if (Recorder.isSilent(audio) || audio.size < SpeechEngine.SAMPLE_RATE / 4) {
            setState(State.IDLE)
            return
        }
        setState(if (SpeechEngine.isLoaded()) State.TRANSCRIBING else State.LOADING)
        worker.execute {
            try {
                if (!SpeechEngine.isLoaded()) {
                    SpeechEngine.preload(app)
                    post(gen) { setState(State.TRANSCRIBING) }
                }
                val raw = SpeechEngine.transcribe(app, audio)
                val settings = Settings.load(app)
                val text = polish(raw, settings, gen)
                post(gen) {
                    setState(State.IDLE)
                    if (text.isNotBlank()) listener.onText(text)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Dictation failed", e)
                post(gen) {
                    setState(State.IDLE)
                    listener.onError(e.message ?: "Something went wrong")
                }
            }
        }
    }

    private fun polish(raw: String, settings: Settings, gen: Int): String {
        val basic = if (settings.basicCleanup) Cleanup.clean(raw) else raw
        if (basic.isBlank() || !settings.smartCleanup || settings.apiKey.isBlank()) return basic
        post(gen) { setState(State.FORMATTING) }
        val t = System.currentTimeMillis()
        val out = Formatter.format(basic, settings)
        Log.i(TAG, "smart clean-up ${System.currentTimeMillis() - t} ms\n  raw: $raw\n  basic: $basic\n  out: $out")
        return out
    }

    /** Stops without typing anything. */
    fun cancel() {
        generation++
        recorder?.stop()
        recorder = null
        if (state != State.IDLE) setState(State.IDLE)
    }

    fun destroy() {
        cancel()
        worker.shutdown()
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    companion object {
        private const val TAG = "SayfluxDictation"
    }
}
