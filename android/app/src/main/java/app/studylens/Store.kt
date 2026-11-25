package app.studylens

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One answered question. */
data class Answer(
    val number: String,
    val question: String,
    val answer: String,      // e.g. "C" or "A, C" or a short phrase
    val answerText: String,  // e.g. "Mitochondrion"
    val explanation: String,
    val type: String = "choice",          // choice | short | work
    val steps: List<String> = emptyList(), // worked steps for calculations
) {
    /** A multiple-choice style answer that fits in a round badge (e.g. "C", "A, C"). */
    val isLetter get() = type == "choice" && answer.length <= 6 && answer.all { it.isLetter() || it == ',' || it == ' ' }
    /** Multi-part answers like "(i) …; (ii) …" split onto separate lines. */
    val parts: List<String> get() = answer.split(Regex(";\\s*|\\n")).map { it.trim() }.filter { it.isNotBlank() }

    fun toJson() = JSONObject().put("number", number).put("question", question)
        .put("answer", answer).put("answerText", answerText).put("explanation", explanation)
        .put("type", type).put("steps", JSONArray().apply { steps.forEach { put(it) } })

    companion object {
        fun from(o: JSONObject) = Answer(
            o.optString("number"), o.optString("question"), o.optString("answer"),
            o.optString("answerText", o.optString("answer_text")), o.optString("explanation"),
            o.optString("type", "choice"),
            o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
        )
    }
}

/** One capture: a single question or a whole screen. */
data class Scan(val id: Long, val fullScreen: Boolean, val answers: List<Answer>) {
    val title: String
        get() = if (fullScreen) "Full screen · ${answers.size} question${if (answers.size == 1) "" else "s"}"
        else answers.firstOrNull()?.question?.ifBlank { null } ?: "One question"

    fun toJson() = JSONObject().put("id", id).put("full", fullScreen)
        .put("answers", JSONArray().apply { answers.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): Scan {
            val arr = o.optJSONArray("answers") ?: JSONArray()
            return Scan(o.optLong("id"), o.optBoolean("full"), (0 until arr.length()).map { Answer.from(arr.getJSONObject(it)) })
        }
    }
}

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("studylens", Context.MODE_PRIVATE)
    private val historyFile get() = File(ctx.filesDir, "history.json")

    var apiKey: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit().putString("key", v.trim()).apply()
    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(v) = prefs.edit().putString("model", v).apply()
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()
    var bubbleOn: Boolean
        get() = prefs.getBoolean("bubbleOn", false)
        set(v) = prefs.edit().putBoolean("bubbleOn", v).apply()
    var bubbleSize: Int // 0 small, 1 medium, 2 large
        get() = prefs.getInt("bubbleSize", 1)
        set(v) = prefs.edit().putInt("bubbleSize", v).apply()
    var explanations: Boolean
        get() = prefs.getBoolean("explain", true)
        set(v) = prefs.edit().putBoolean("explain", v).apply()
    var bubbleY: Int
        get() = prefs.getInt("bubbleY", 600)
        set(v) = prefs.edit().putInt("bubbleY", v).apply()

    fun history(): List<Scan> = runCatching {
        val arr = JSONArray(historyFile.readText())
        (0 until arr.length()).map { Scan.from(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(scan: Scan, shot: Bitmap?) {
        val list = listOf(scan) + history().take(199)
        historyFile.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        shot?.let { b -> runCatching { File(ctx.filesDir, "shot_${scan.id}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) } } }
    }

    fun shotFile(id: Long) = File(ctx.filesDir, "shot_$id.jpg")

    fun delete(id: Long) {
        val list = history().filter { it.id != id }
        historyFile.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        shotFile(id).delete()
    }

    fun clear() {
        historyFile.delete()
        ctx.filesDir.listFiles { f -> f.name.startsWith("shot_") }?.forEach { it.delete() }
    }
}
