package com.dialorsd.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Action(val type: String, val seconds: Long = 0, val label: String = "", val prompt: String = "")
data class Reply(val say: String, val actions: List<Action>)

enum class Failure { NO_KEY, BAD_KEY, QUOTA, NETWORK, SERVER, MODEL, OTHER }

class BrainException(val kind: Failure, message: String) : Exception(message)

/** The "brain": Gemini generateContent over HTTPS, with Groq as an optional backup. Blocking: call off the main thread. */
object Brain {

    // ---------- public API ----------

    fun chat(s: Settings, history: List<Msg>, userText: String, timers: List<TimerItem>): Reply {
        val turns = history.filter { it.role != "note" }.takeLast(Defaults.HISTORY_TURNS * 2)
            .map { (if (it.role == "user") "user" else "model") to it.text } + ("user" to userText)
        val text = generate(s, chatPrompt(s, timers), turns, json = true)
        return parseReply(text)
    }

    /** Runs a background task. Returns (spoken summary, full result). */
    fun task(s: Settings, prompt: String): Pair<String, String> {
        val sys = character(s) + """

You are now doing a background task the user delegated to you. Do it thoroughly and accurately.
You cannot browse the internet; if the task needs live data, say so honestly and do your best from knowledge.
Answer in the language of the task text (English or Ukrainian).
Output ONLY a JSON object: {"summary": "<at most 3 short spoken sentences addressed to the user>", "result": "<the full result as plain text, no markdown tables>"}"""
        val text = generate(s, sys, listOf("user" to prompt), json = true)
        val o = parseJsonObject(text)
        val summary = o?.optString("summary").orEmpty().ifBlank { text.take(300) }
        val result = o?.optString("result").orEmpty().ifBlank { text }
        return summary to result
    }

    /** A short line for when the user picks up a timer call. */
    fun callLine(s: Settings, label: String, ukrainian: Boolean): String {
        val lang = if (ukrainian) "Ukrainian" else "English"
        val sys = character(s) + """

You are phoning the user because a timer they asked for has just finished.
Write ONE short spoken line (max 2 sentences) in $lang that tells them the timer "$label" is done.
Output ONLY a JSON object: {"say": "..."}"""
        val text = generate(s, sys, listOf("user" to "(The user has just picked up the call.)"), json = true)
        return parseReply(text).say
    }

    fun fallbackCallLine(label: String, ukrainian: Boolean): String =
        if (ukrainian) "Сер, ваш таймер «$label» завершився."
        else "Sir, your timer \"$label\" has finished."

    /** In-character explanation of a failure, in the user's language. */
    fun errorLine(e: Throwable, ukrainian: Boolean): String {
        val kind = (e as? BrainException)?.kind ?: if (e is IOException) Failure.NETWORK else Failure.OTHER
        return if (ukrainian) when (kind) {
            Failure.NO_KEY -> "Сер, я не маю ключа Gemini API. Додайте його в налаштуваннях, будь ласка."
            Failure.BAD_KEY -> "Сер, ключ Gemini API не приймається. Перевірте його в налаштуваннях."
            Failure.QUOTA -> "Сер, безкоштовний ліміт запитів на сьогодні вичерпано. Спробуймо трохи пізніше."
            Failure.NETWORK -> "Сер, здається, немає з'єднання з інтернетом."
            Failure.SERVER -> "Сер, сервери Google зараз перевантажені. Спробуйте за хвилину."
            Failure.MODEL -> "Сер, модель не знайдено. Перевірте назву моделі в налаштуваннях."
            Failure.OTHER -> "Сер, щось пішло не так: ${e.message?.take(160)}"
        } else when (kind) {
            Failure.NO_KEY -> "I'm afraid I have no Gemini API key, sir. Please add one in Settings."
            Failure.BAD_KEY -> "The Gemini API key was refused, sir. Do check it in Settings."
            Failure.QUOTA -> "We've exhausted today's free quota, sir. Shall we try again a little later?"
            Failure.NETWORK -> "I seem to be cut off from the internet, sir."
            Failure.SERVER -> "Google's servers are rather overwhelmed at the moment, sir. Try again in a minute."
            Failure.MODEL -> "That model doesn't appear to exist, sir. Please check the model name in Settings."
            Failure.OTHER -> "Something went wrong, sir: ${e.message?.take(160)}"
        }
    }

    // ---------- prompts ----------

    private fun character(s: Settings): String {
        val h = s.humour
        val tone = when {
            h < 0.2f -> "Strictly professional and concise. No jokes."
            h < 0.45f -> "Polite and composed, with the occasional very dry remark."
            h < 0.75f -> "Dry British wit is welcome: understated, deadpan, never rude."
            else -> "Openly sarcastic and teasing, but always loyal, helpful and never cruel."
        }
        return """You are Jarvis, a personal AI assistant on the user's Android phone, speaking in the manner of a calm,
impeccably polite British butler. Address the user as "sir" in English and "сер" in Ukrainian.
Humour setting: $tone
Always reply in the language of the user's latest message (English or Ukrainian).
Your replies are spoken aloud, so keep them short and natural: usually 1-3 sentences, no markdown, no emojis, no lists unless asked."""
    }

    private fun chatPrompt(s: Settings, timers: List<TimerItem>): String {
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val active = if (timers.isEmpty()) "none" else timers.joinToString("; ") {
            if (it.kind == "task") "task \"${it.label}\" (running)"
            else "timer \"${it.label}\" (${((it.endAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s left)"
        }
        return character(s) + """

Current local time: $now. Active timers and tasks: $active.

You can act through these actions only:
- {"type":"timer","seconds":N,"label":"short name"}: set a countdown timer; when it ends you will phone the user.
- {"type":"cancel_timer","label":"name of the timer, or all"}
- {"type":"task","prompt":"a complete, self-contained instruction","label":"short name"}: for anything that needs
  long thinking or writing (plans, research from your own knowledge, drafts, long explanations). You will do it in the
  background and phone the user with the result. In "say", briefly confirm you are on it.
You cannot access the internet, the user's contacts, messages, calendar or other apps. Never claim to have done
something you did not do. Convert spoken durations to seconds exactly (e.g. "10 minutes" = 600).

Output ONLY a JSON object, nothing else: {"say": "what you say aloud", "actions": [ ... ]} ("actions" may be empty)."""
    }

    // ---------- parsing ----------

    fun parseReply(raw: String): Reply {
        val o = parseJsonObject(raw) ?: return Reply(stripFences(raw).trim(), emptyList())
        val say = o.optString("say").ifBlank { if (o.has("actions")) "" else stripFences(raw) }
        val acts = mutableListOf<Action>()
        val arr = o.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val type = a.optString("type")
            val secs = a.optDouble("seconds", 0.0).let { if (it.isNaN()) 0L else it.toLong() }
            acts += Action(type, secs, a.optString("label"), a.optString("prompt"))
        }
        return Reply(say.trim(), acts)
    }

    private fun stripFences(s: String): String =
        s.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()

    private fun parseJsonObject(raw: String): JSONObject? {
        val t = stripFences(raw)
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull()
    }

    // ---------- transport ----------

    /** turns: list of (role "user"/"model", text). Returns raw model text. */
    private fun generate(s: Settings, system: String, turns: List<Pair<String, String>>, json: Boolean): String {
        if (s.geminiKey.isBlank()) throw BrainException(Failure.NO_KEY, "No Gemini API key")
        val models = listOf(s.model, s.fallbackModel).filter { it.isNotBlank() }.distinct()
        var first: BrainException? = null
        for (m in models) {
            try {
                return gemini(s.geminiKey, m, system, turns, json)
            } catch (e: BrainException) {
                if (first == null) first = e
                // Only worth trying another model for quota / overload / unknown model.
                if (e.kind !in setOf(Failure.QUOTA, Failure.SERVER, Failure.MODEL)) break
            }
        }
        val err = first ?: BrainException(Failure.OTHER, "No model configured")
        if (s.groqKey.isNotBlank() && err.kind in setOf(Failure.QUOTA, Failure.SERVER, Failure.MODEL)) {
            try {
                return groq(s.groqKey, s.groqModel, system, turns)
            } catch (_: BrainException) {
                // Report the original Gemini problem; it is the one the user can act on.
            }
        }
        throw err
    }

    private fun gemini(key: String, model: String, system: String, turns: List<Pair<String, String>>, json: Boolean): String {
        val contents = JSONArray()
        // Merge consecutive turns from the same role; the API expects alternating roles.
        var lastRole = ""
        var buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) contents.put(JSONObject().put("role", lastRole)
                .put("parts", JSONArray().put(JSONObject().put("text", buf.toString()))))
        }
        for ((role, text) in turns) {
            if (role != lastRole) { flush(); lastRole = role; buf = StringBuilder(text) } else buf.append("\n").append(text)
        }
        flush()
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val resp = post(Defaults.GEMINI_BASE + model + ":generateContent", body.toString(), mapOf("x-goog-api-key" to key))
        val o = JSONObject(resp)
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        if (sb.isBlank()) {
            val reason = o.optJSONObject("promptFeedback")?.optString("blockReason")
                ?: o.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
            throw BrainException(Failure.OTHER, "Empty answer from Gemini (${reason ?: "no reason"})")
        }
        return sb.toString()
    }

    private fun groq(key: String, model: String, system: String, turns: List<Pair<String, String>>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { (r, t) -> msgs.put(JSONObject().put("role", if (r == "model") "assistant" else "user").put("content", t)) }
        val body = JSONObject().put("model", model).put("messages", msgs)
        val resp = post(Defaults.GROQ_URL, body.toString(), mapOf("Authorization" to "Bearer $key"))
        return JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: throw BrainException(Failure.OTHER, "Empty answer from Groq")
    }

    /** POST JSON; maps HTTP/network errors to [BrainException]. Never logs headers or keys. */
    fun post(url: String, json: String, headers: Map<String, String>, readTimeoutMs: Int = 90_000): String {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw BrainException(Failure.NETWORK, e.message ?: "network")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            val kind = when {
                code == 429 -> Failure.QUOTA
                code == 401 || code == 403 -> Failure.BAD_KEY
                code == 400 && (msg.contains("API key", true) || msg.contains("API_KEY", true)) -> Failure.BAD_KEY
                code == 404 -> Failure.MODEL
                code >= 500 -> Failure.SERVER
                else -> Failure.OTHER
            }
            throw BrainException(kind, msg)
        } catch (e: BrainException) {
            throw e
        } catch (e: UnknownHostException) {
            throw BrainException(Failure.NETWORK, "No internet")
        } catch (e: SocketTimeoutException) {
            throw BrainException(Failure.SERVER, "Timed out")
        } catch (e: IOException) {
            throw BrainException(Failure.NETWORK, e.message ?: "network")
        } finally {
            conn.disconnect()
        }
    }
}
