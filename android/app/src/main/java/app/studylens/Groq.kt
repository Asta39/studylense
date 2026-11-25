package app.studylens

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class GroqException(msg: String) : Exception(msg)

/** Talks to Groq's OpenAI-compatible API. Call from a background thread. */
class Groq(private val store: Store) {
    private val base = "https://api.groq.com/openai/v1"

    fun answer(image: Bitmap, fullScreen: Boolean): List<Answer> {
        val key = store.apiKey.ifBlank { throw GroqException("Add your Groq API key in Settings first.") }
        val jpg = encode(image)
        val cached = store.model
        val models = if (cached.isNotBlank()) listOf(cached) else visionModels(key)
        var last: Exception = GroqException("No model available")
        for (m in models) {
            try {
                val result = ask(jpg, m, key, fullScreen)
                store.model = m
                return result
            } catch (e: GroqException) {
                last = e
                store.model = ""
                // Rate limits aren't the model's fault, don't try others.
                if (e.message?.contains("rate", true) == true || e.message?.contains("per minute", true) == true) throw e
            }
        }
        throw last
    }

    /** Image-capable models this key can use, best first. */
    fun visionModels(key: String): List<String> {
        val json = request("$base/models", key, null)
        val data = json.optJSONArray("data") ?: JSONArray()
        val ids = (0 until data.length()).map { data.getJSONObject(it).optString("id") }
        val prefs = listOf("llama-4-maverick", "llama-4-scout", "llama-4", "vision", "-vl", "gemma-3", "qwen3.8", "qwen")
        val out = mutableListOf<String>()
        for (p in prefs) ids.filter { it.lowercase().contains(p) && it !in out }.forEach { out += it }
        if (out.isEmpty()) throw GroqException("Your Groq key has no model that can read images. Models: ${ids.joinToString()}")
        return out
    }

    private fun ask(jpg: String, model: String, key: String, fullScreen: Boolean): List<Answer> {
        val explain = if (store.explanations) "one short sentence on why it is correct" else ""
        val scope = if (fullScreen) "Find EVERY question visible in the screenshot (often 2-3, may have parts (i), (ii)…) and answer each one."
        else "The screenshot is cropped around ONE question (it may have parts (i), (ii)…). Answer it."
        val prompt = """
            $scope
            Solve carefully and check your work BEFORE writing. Output only final, verified results — never write corrections, "wait", or recalculations.
            Reply with JSON only, no markdown fences, in exactly this shape:
            {"answers":[{"number":"1","question":"<question, max 15 words>","type":"choice|short|work","answer":"<see below>","answerText":"<option text for choice, else empty>","steps":["<step>"],"explanation":"$explain"}]}
            type "choice": multiple choice — answer is the letter(s) only, e.g. "C" or "A, C".
            type "short": a word/phrase/definition — answer is that short text.
            type "work": a calculation or multi-part problem — answer is the final result(s), one per part separated by "; " like "(i) π = 45q − 2q² + 16; (ii) q = 11.25; (iii) 269.13". steps = 2-5 short worked steps.
            If there are no questions, reply {"answers":[]}.
        """.trimIndent()

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_completion_tokens", if (fullScreen) 950 else 700)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpg"))))))

        val json = request("$base/chat/completions", key, body)
        val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw GroqException("Unexpected response from Groq")
        return parse(text)
    }

    private fun parse(raw: String): List<Answer> {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("```json", "").replace("```", "").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val parsed = runCatching {
                val arr = JSONObject(text.substring(start, end + 1)).getJSONArray("answers")
                (0 until arr.length()).map { Answer.from(arr.getJSONObject(it)) }
            }.getOrNull()
            if (parsed != null) {
                if (parsed.isEmpty()) throw GroqException("No questions found on screen. Try One question and drag a box around it.")
                return parsed
            }
        }
        // Fallback: show whatever the model said.
        return listOf(Answer("1", "", "See explanation", "", text, "short"))
    }

    private fun request(url: String, key: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) {
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) {
            val msg = json.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "HTTP $code"
            throw GroqException(if (code == 429) "Groq rate limit reached — wait a minute. ($msg)" else "Groq error: $msg")
        }
        return json
    }

    /** JPEG, longest side at most 2400px (full phone resolution, so small text stays readable). */
    private fun encode(src: Bitmap): String {
        val scale = minOf(1f, 2400f / maxOf(src.width, src.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
