package app.sayflux.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Records 16 kHz mono audio from the mic until stop() is called. */
class Recorder(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<ShortArray>()

    @SuppressLint("MissingPermission") // checked by the caller before recording
    fun start() {
        val rate = SpeechEngine.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, rate / 5 * 2), // at least 200 ms
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("The microphone is busy or unavailable")
        }
        synchronized(chunks) { chunks.clear() }
        record = r
        running = true
        r.startRecording()
        thread = Thread({ loop(r) }, "sayflux-recorder").also { it.start() }
    }

    private fun loop(r: AudioRecord) {
        val frame = ShortArray(SpeechEngine.SAMPLE_RATE / 20) // 50 ms
        var total = 0
        while (running && total < MAX_SAMPLES) {
            val n = r.read(frame, 0, frame.size)
            if (n <= 0) continue
            synchronized(chunks) { chunks.add(frame.copyOf(n)) }
            total += n
            onLevel(level(frame, n))
        }
    }

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val parts = synchronized(chunks) { chunks.toList().also { chunks.clear() } }
        val out = FloatArray(parts.sumOf { it.size })
        var i = 0
        for (part in parts) for (s in part) out[i++] = s / 32768f
        return out
    }

    companion object {
        private const val MAX_SAMPLES = SpeechEngine.SAMPLE_RATE * 60 * 10 // 10 minutes

        // Same idea as the desktop app's silence_threshold_db: skip near-silent recordings.
        private const val SILENCE_DB = -50.0

        fun isSilent(samples: FloatArray): Boolean {
            if (samples.isEmpty()) return true
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = sqrt(sum / samples.size)
            return rms <= 0 || 20 * log10(rms) < SILENCE_DB
        }

        /** Rough loudness from 0 to 1 for the mic animation. */
        private fun level(frame: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
            val rms = sqrt(sum / n) / 32768.0
            if (rms <= 0) return 0f
            val db = 20 * log10(rms) // about -60 (quiet room) to -10 (loud speech)
            return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
        }
    }
}
