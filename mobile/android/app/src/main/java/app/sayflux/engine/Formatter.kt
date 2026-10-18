package app.sayflux.engine

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Smart clean-up with the Gemini API. Port of the desktop app's formatter
 * (src/sayflux/formatter.py): only the text is sent, never audio, and on any failure the
 * text is returned unchanged, so dictation never gets lost.
 */
object Formatter {
    private const val TAG = "SayfluxFormatter"
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val THINKING_LEVEL = "MINIMAL"
    private const val TIMEOUT_MS = 10_000

    private class HttpError(val code: Int, val body: String) : IOException("HTTP $code")

    /** Call from a background thread. */
    fun format(text: String, settings: Settings): String {
        if (settings.apiKey.isBlank() || text.isBlank()) return text

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Prompt.system(settings)))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", Prompt.message(text)))),
                ),
            )
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", THINKING_LEVEL)))

        val url = API_URL.format(settings.model)
        val out = try {
            val data = try {
                request(url, settings.apiKey, body)
            } catch (e: HttpError) {
                // A model that doesn't accept this thinking level answers 400: retry without it.
                if (e.code != 400) throw e
                Log.w(TAG, "Gemini rejected thinkingLevel, retrying without it: ${e.body.take(300)}")
                body.remove("generationConfig")
                request(url, settings.apiKey, body)
            }
            val parts = data.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }.trim()
        } catch (e: HttpError) {
            Log.e(TAG, "Gemini HTTP ${e.code}: ${e.body.take(300)}")
            return text
        } catch (e: IOException) {
            Log.e(TAG, "Gemini clean-up failed, typing the text as is", e)
            return text
        } catch (e: JSONException) {
            Log.e(TAG, "Unexpected Gemini response, typing the text as is", e)
            return text
        }
        return if (Prompt.looksRight(out, text)) out else text
    }

    private fun request(url: String, key: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw HttpError(code, err)
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
