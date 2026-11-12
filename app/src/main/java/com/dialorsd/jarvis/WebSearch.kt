package com.dialorsd.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Web lookup as an ordered provider chain:
 *  1. Gemini Google Search grounding (search model, then 2.5 flash-lite). Free only on keys that may still use Gemini 2.5.
 *  2. Groq built-in browser search (openai/gpt-oss-120b, then 20b), if a Groq key is saved. https://console.groq.com/docs/browser-search
 *  3. Wikipedia (encyclopedic only).
 * Models the API refuses for good reasons are remembered (Settings.blockSearchModel) and skipped for a week.
 */
object WebSearch {
    private const val UA = "JarvisAssistant/4 (personal Android app; Wikipedia summary lookups)"

    private val CITATION = Regex("【[^】]*】")
    private val URL_RE = Regex("https?://[^\\s)\\]>\"'】]+")

    /** Statuses for the Settings screen: provider label to status text. */
    fun status(s: Settings): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in geminiModels(s)) {
            val label = "Gemini search: $m"
            out += label to when {
                s.geminiKey.isBlank() -> "no key"
                s.searchBlockReason(m) != null -> "skipped: ${s.searchBlockReason(m)}"
                else -> okOrReady(s, "gemini:$m")
            }
        }
        for (m in groqModels(s)) {
            out += "Groq browser search: $m" to if (s.groqKey.isBlank()) "no key" else okOrReady(s, "groq:$m")
        }
        out += "Wikipedia (encyclopedia only)" to okOrReady(s, "wiki")
        return out
    }

    private fun okOrReady(s: Settings, id: String): String {
        val t = s.searchLastOk(id)
        return if (t > 0) "OK (worked ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))})" else "ready (not tested yet)"
    }

    private fun geminiModels(s: Settings) = listOf(s.searchModel, Defaults.SEARCH_FALLBACK_MODEL).filter { it.isNotBlank() }.distinct()
    private fun groqModels(s: Settings) = listOf(s.groqSearchModel, s.groqSearchFallback).filter { it.isNotBlank() }.distinct()

    fun lookup(s: Settings, query: String): Lookup {
        val q = query.trim().ifBlank { throw BrainException(Failure.OTHER, "empty search query") }
        val trail = mutableListOf<String>()
        var firstKind: Failure? = null
        fun fail(name: String, e: BrainException) { trail += "$name: ${e.message?.take(140)}"; if (firstKind == null) firstKind = e.kind }
        fun done(r: Lookup, id: String): Lookup {
            s.markSearchOk(id)
            return r.copy(notes = trail.joinToString("\n"))
        }

        // 1. Gemini grounding
        if (s.geminiKey.isBlank()) trail += "Gemini: no key" else for (m in geminiModels(s)) {
            s.searchBlockReason(m)?.let { trail += "Gemini $m: skipped ($it)"; continue }
            try {
                return done(gemini(s, m, q), "gemini:$m")
            } catch (e: BrainException) {
                fail("Gemini $m", e)
                if (shouldBlock(e)) s.blockSearchModel(m, e.message.orEmpty())
            }
        }
        // 2. Groq browser search
        if (s.groqKey.isBlank()) trail += "Groq: no key" else for (m in groqModels(s)) {
            try {
                return done(groq(s, m, q), "groq:$m")
            } catch (e: BrainException) {
                fail("Groq $m", e)
                if (e.kind == Failure.BAD_KEY) break
            }
        }
        // 3. Wikipedia
        try {
            return done(wikipedia(q), "wiki")
        } catch (e: BrainException) {
            fail("Wikipedia", e)
        }
        throw BrainException(firstKind ?: Failure.OTHER, "Could not look it up. " + trail.joinToString("; "))
    }

    /** Errors that mean "this model will keep failing" rather than a temporary hiccup. */
    internal fun shouldBlock(e: BrainException): Boolean {
        val m = e.message.orEmpty().lowercase()
        if (e.kind == Failure.MODEL) return true
        if (e.kind == Failure.BAD_KEY || e.kind == Failure.NETWORK || e.kind == Failure.SERVER) return false
        return listOf("no longer available", "not found", "is not supported", "not available", "grounding", "search tool",
            "limit: 0", "only available to", "not allowed").any { it in m }
    }

    // ---------------- Gemini ----------------

    private fun gemini(s: Settings, model: String, query: String): Lookup {
        val sys = "Search the web and answer the query with concise, factual, up-to-date information " +
            "(include dates, numbers, names). Answer in the language of the query. Plain text, no markdown."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", query)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val o = JSONObject(Brain.post(Defaults.GEMINI_BASE + model + ":generateContent", body.toString(), mapOf("x-goog-api-key" to s.geminiKey)))
        val cand = o.optJSONArray("candidates")?.optJSONObject(0)
        val text = Brain.partsText(cand)
        if (text.isBlank()) throw BrainException(Failure.OTHER, "empty search answer")
        val sources = mutableListOf<Pair<String, String>>()
        val chunks = cand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val w = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = w.optString("uri")
            if (uri.isNotBlank() && sources.none { it.second == uri }) sources += w.optString("title").ifBlank { uri } to uri
        }
        return Lookup(text.trim(), sources.take(6), "Gemini Google Search ($model)")
    }

    // ---------------- Groq browser search ----------------

    private fun groq(s: Settings, model: String, query: String): Lookup {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "Answer concisely and factually using web search. Give the key facts and dates only, at most 5 sentences. " +
                    "Answer in the language of the question. Plain text, no markdown, no tables."))
                .put(JSONObject().put("role", "user").put("content", query.take(400))))
            .put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
            .put("tool_choice", "required")
            .put("reasoning_effort", "low")
            .put("max_completion_tokens", 1024)
        val headers = mapOf("Authorization" to "Bearer ${s.groqKey}")
        val resp = try {
            Brain.post(Defaults.GROQ_URL, body.toString(), headers)
        } catch (e: BrainException) {
            // Rate limit / tokens-per-minute: wait briefly and try once more, then let the chain move on.
            val tpm = e.message.orEmpty().contains("token", true) && e.message.orEmpty().contains("limit", true)
            if (e.kind == Failure.QUOTA || tpm || e.message.orEmpty().contains("413")) {
                Thread.sleep(4000)
                Brain.post(Defaults.GROQ_URL, body.toString(), headers)
            } else throw e
        }
        val msg = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw BrainException(Failure.OTHER, "no message in Groq reply")
        val raw = msg.optString("content")
        val text = raw.replace(CITATION, "").replace("**", "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex(" {2,}"), " ").trim()
        if (text.isBlank()) throw BrainException(Failure.OTHER, "empty Groq answer")
        // Sources: whatever structured results the response carries (executed_tools or similar); else URLs in the text; else none.
        val found = linkedMapOf<String, String>()
        collectUrls(msg.opt("executed_tools"), found)
        collectUrls(msg.opt("annotations"), found)
        if (found.isEmpty()) URL_RE.findAll(raw).forEach { found.putIfAbsent(it.value.trimEnd('.', ',', ';'), it.value.trimEnd('.', ',', ';')) }
        val cleanText = text.replace(URL_RE, "").replace(Regex("\\(\\s*\\)"), "").replace(Regex(" {2,}"), " ").trim().ifBlank { text }
        return Lookup(cleanText, found.entries.take(6).map { it.value to it.key }, "Groq browser search ($model)")
    }

    /** Walks arbitrary JSON and collects objects that carry a "url" (and optionally a "title"). */
    private fun collectUrls(node: Any?, out: MutableMap<String, String>, depth: Int = 0) {
        if (depth > 8 || out.size >= 12) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectUrls(node.opt(i), out, depth + 1)
            is JSONObject -> {
                val url = node.optString("url")
                if (url.startsWith("http")) out.putIfAbsent(url, node.optString("title").ifBlank { url })
                node.keys().forEach { collectUrls(node.opt(it), out, depth + 1) }
            }
        }
    }

    // ---------------- Wikipedia ----------------

    private fun wikipedia(query: String): Lookup {
        val lang = if (query.any { it in 'Ѐ'..'ӿ' }) "uk" else "en"
        val host = "https://$lang.wikipedia.org"
        val enc = URLEncoder.encode(query.take(200), "UTF-8")
        val hits = JSONObject(get("$host/w/api.php?action=query&list=search&srsearch=$enc&srlimit=1&format=json")).optJSONObject("query")
            ?.optJSONArray("search")
        val title = hits?.optJSONObject(0)?.optString("title").orEmpty()
        if (title.isBlank()) throw BrainException(Failure.OTHER, "no Wikipedia article found")
        val sum = JSONObject(get("$host/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")))
        val extract = sum.optString("extract").trim()
        if (extract.isBlank()) throw BrainException(Failure.OTHER, "empty Wikipedia summary")
        val url = sum.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            .ifBlank { "$host/wiki/" + title.replace(' ', '_') }
        val text = "(Wikipedia only; this is an encyclopedia summary and may be outdated, not live news.) " +
            "${sum.optString("title").ifBlank { title }}: $extract"
        return Lookup(text.take(2500), listOf(title to url), "Wikipedia ($lang, encyclopedia only)")
    }

    private fun get(url: String): String {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) {
            throw BrainException(Failure.NETWORK, e.message ?: "network")
        }
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw BrainException(if (code >= 500) Failure.SERVER else Failure.OTHER, "Wikipedia HTTP $code")
            return text
        } catch (e: BrainException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw BrainException(Failure.NETWORK, "No internet")
        } catch (e: Exception) {
            throw BrainException(Failure.NETWORK, e.message ?: "network")
        } finally {
            conn.disconnect()
        }
    }
}
