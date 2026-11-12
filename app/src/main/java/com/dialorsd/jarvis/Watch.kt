package com.dialorsd.jarvis

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/*
 * v4: "Call me when..." watchers. Each active watcher is one WorkManager periodic job (minimum 15 min,
 * default 30, network required). Each check is ONE GET with a 15 s timeout and a normal browser
 * User-Agent; HTTP errors back off exponentially. When the condition is met Jarvis rings with the usual
 * call screen. One-shot by default (removed after it fires); "repeat" watchers ring on each change.
 * The number tests (price_below, number_below/above) are simple text matching and only work on simple
 * pages whose HTML contains the number (not pages that load prices with JavaScript).
 */

data class Watcher(
    val id: Int, val kind: String, val target: String, val condition: String, val threshold: Double?,
    val label: String, val intervalMin: Int, val repeat: Boolean, val created: Long,
    val lastCheck: Long = 0, val lastResult: String = "", val lastHash: String = "", val lastState: Boolean? = null,
    val fails: Int = 0,
)

object Watchers {
    const val MAX_ACTIVE = 10
    const val MIN_INTERVAL = 15
    const val DEFAULT_INTERVAL = 30
    val KINDS = listOf("site_up", "page_contains", "page_not_contains", "page_changed", "price_below", "number_below", "number_above")
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 Jarvis/4"

    val items = mutableStateListOf<Watcher>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis_watch", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        items.clear()
        items.addAll(readAll(ctx))
    }

    @Synchronized
    private fun readAll(ctx: Context): List<Watcher> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Watcher(o.getInt("id"), o.optString("kind"), o.optString("target"), o.optString("cond"),
                if (o.has("thr")) o.optDouble("thr") else null, o.optString("label"), o.optInt("int", DEFAULT_INTERVAL),
                o.optBoolean("rep"), o.optLong("created"), o.optLong("lc"), o.optString("lr"), o.optString("lh"),
                if (o.has("ls")) o.optBoolean("ls") else null, o.optInt("fails"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun writeAll(ctx: Context, list: List<Watcher>) {
        val arr = JSONArray()
        list.forEach { w ->
            val o = JSONObject().put("id", w.id).put("kind", w.kind).put("target", w.target).put("cond", w.condition)
                .put("label", w.label).put("int", w.intervalMin).put("rep", w.repeat).put("created", w.created)
                .put("lc", w.lastCheck).put("lr", w.lastResult).put("lh", w.lastHash).put("fails", w.fails)
            w.threshold?.let { o.put("thr", it) }
            w.lastState?.let { o.put("ls", it) }
            arr.put(o)
        }
        prefs(ctx).edit().putString("items", arr.toString()).commit()
        Store.runOnMain { items.clear(); items.addAll(list) }
    }

    fun list(ctx: Context): List<Watcher> = readAll(ctx)

    @Synchronized
    private fun put(ctx: Context, w: Watcher) = writeAll(ctx, readAll(ctx).filter { it.id != w.id } + w)

    @Synchronized
    fun remove(ctx: Context, id: Int): Watcher? {
        val all = readAll(ctx)
        val w = all.firstOrNull { it.id == id }
        writeAll(ctx, all.filter { it.id != id })
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }
        return w
    }

    /** Removes watchers whose label or URL contains [match]; "all" removes every one. */
    fun removeByMatch(ctx: Context, match: String): List<Watcher> {
        val m = match.trim().lowercase()
        val all = m.isEmpty() || m == "all" || m == "всі" || m == "усі"
        val hits = readAll(ctx).filter { all || it.label.lowercase().contains(m) || it.target.lowercase().contains(m) || it.id.toString() == m }
        hits.forEach { remove(ctx, it.id) }
        return hits
    }

    private fun workName(id: Int) = "watch-$id"

    private fun enqueue(ctx: Context, w: Watcher) {
        val net = if (Settings(ctx).watchWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = PeriodicWorkRequestBuilder<WatchWorker>(w.intervalMin.toLong(), TimeUnit.MINUTES)
            .setInitialDelay(w.intervalMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setInputData(workDataOf("id" to w.id))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(workName(w.id), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Re-enqueues all (after the Wi-Fi setting changes). WorkManager itself survives reboots. */
    fun rescheduleAll(ctx: Context) = readAll(ctx).forEach { runCatching { enqueue(ctx, it) } }

    /**
     * Validates, runs a first check immediately and saves the watcher. Blocking (network).
     * Returns a short result line for the user/model.
     */
    fun add(ctx: Context, kindIn: String, targetIn: String, condition: String, thresholdIn: Double?, labelIn: String,
            intervalIn: Int?, repeat: Boolean): String {
        val kind = kindIn.trim().lowercase()
        if (kind !in KINDS) return "Unknown watcher kind \"$kindIn\". Use one of: ${KINDS.joinToString()}."
        var target = targetIn.trim()
        if (target.isEmpty()) return "A watcher needs a web address (target)."
        if (!target.startsWith("http://") && !target.startsWith("https://")) target = "https://$target"
        runCatching { URL(target) }.onFailure { return "\"$targetIn\" is not a valid web address." }
        val numeric = kind in setOf("price_below", "number_below", "number_above")
        val threshold = thresholdIn ?: if (numeric) condition.replace(',', '.').toDoubleOrNull() else null
        if (numeric && threshold == null) return "This watcher needs a number (threshold), e.g. 500."
        if (kind in setOf("page_contains", "page_not_contains") && condition.isBlank()) return "This watcher needs the phrase to look for (condition)."
        val existing = readAll(ctx)
        if (existing.size >= MAX_ACTIVE) return "There are already $MAX_ACTIVE watchers (the maximum). Remove one first."
        val interval = (intervalIn ?: DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, 24 * 60)
        val label = labelIn.trim().ifBlank { "$kind ${URL(target).host}" }.take(80)
        val cond = if (numeric && condition.replace(',', '.').toDoubleOrNull() != null) "" else condition.trim().take(200)
        var w = Watcher(Store.nextId(), kind, target, cond, threshold, label, interval, repeat, System.currentTimeMillis())
        val r = check(w)
        w = w.copy(lastCheck = System.currentTimeMillis(), lastResult = r.summary, lastHash = r.hash, lastState = r.state,
            fails = if (r.error) 1 else 0)
        if (r.state == true && !repeat && kind != "page_changed") {
            EventLog.add(ctx, "watch", "Watcher \"$label\" was already true when added: ${r.summary}")
            return "Checked now: the condition is ALREADY true (${r.summary}), so no watcher was saved."
        }
        put(ctx, w)
        enqueue(ctx, w)
        return "Watcher \"$label\" saved (#${w.id}), checking every $interval min. First check: ${r.summary}." +
            if (numeric) " Note: number watchers only work on simple pages that show the number in their HTML." else ""
    }

    fun describe(w: Watcher): String = when (w.kind) {
        "site_up" -> "${w.target} is ${if (w.condition.equals("down", true)) "down" else "up"}"
        "page_contains" -> "page contains \"${w.condition}\""
        "page_not_contains" -> "page no longer contains \"${w.condition}\""
        "page_changed" -> "page changes"
        "price_below", "number_below" -> "number below ${fmt(w.threshold)}" + if (w.condition.isNotBlank()) " (near \"${w.condition}\")" else ""
        "number_above" -> "number above ${fmt(w.threshold)}" + if (w.condition.isNotBlank()) " (near \"${w.condition}\")" else ""
        else -> w.kind
    }

    private fun fmt(d: Double?) = d?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: "?"

    fun forPrompt(ctx: Context): String {
        val all = readAll(ctx)
        if (all.isEmpty()) return "No active watchers."
        val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US)
        return "Active watchers:\n" + all.joinToString("\n") {
            "- #${it.id} \"${it.label}\": ${describe(it)} at ${it.target}, every ${it.intervalMin} min" +
                (if (it.repeat) ", repeating" else "") +
                (if (it.lastCheck > 0) "; last check ${f.format(java.util.Date(it.lastCheck))}: ${it.lastResult}" else "")
        }
    }

    // ---------------------------------------------------------------- checking

    class CheckResult(val state: Boolean?, val summary: String, val hash: String = "", val error: Boolean = false)

    /** One GET and evaluation. state = condition met (null = could not tell). Blocking. */
    fun check(w: Watcher): CheckResult {
        val page = try { fetch(w.target) } catch (e: IOException) {
            return if (w.kind == "site_up") {
                val wantDown = w.condition.equals("down", true)
                CheckResult(wantDown, "not reachable (${e.message?.take(80)})")
            } else CheckResult(null, "error: ${e.message?.take(100)}", w.lastHash, error = true)
        }
        if (w.kind == "site_up") {
            val up = page.code in 200..399
            val wantDown = w.condition.equals("down", true)
            return CheckResult(if (wantDown) !up else up, if (up) "up (HTTP ${page.code})" else "down (HTTP ${page.code})")
        }
        if (page.code !in 200..399) return CheckResult(null, "HTTP ${page.code}", w.lastHash, error = true)
        val text = visibleText(page.body)
        val hash = sha(text)
        return when (w.kind) {
            "page_contains" -> {
                val hit = text.contains(w.condition, ignoreCase = true)
                CheckResult(hit, if (hit) "\"${w.condition}\" found" else "\"${w.condition}\" not found", hash)
            }
            "page_not_contains" -> {
                val hit = text.contains(w.condition, ignoreCase = true)
                CheckResult(!hit, if (hit) "\"${w.condition}\" still there" else "\"${w.condition}\" gone", hash)
            }
            "page_changed" -> {
                if (w.lastHash.isBlank()) CheckResult(false, "baseline saved (${text.length} chars of text)", hash)
                else if (hash != w.lastHash) CheckResult(true, "the page text changed", hash)
                else CheckResult(false, "unchanged", hash)
            }
            else -> {
                val n = findNumber(text, w.condition) ?: return CheckResult(null, "no number found" +
                    (if (w.condition.isNotBlank()) " near \"${w.condition}\"" else ""), hash)
                val thr = w.threshold ?: return CheckResult(null, "no threshold", hash)
                val met = if (w.kind == "number_above") n > thr else n < thr
                CheckResult(met, "found ${fmt(n)}", hash)
            }
        }
    }

    class Page(val code: Int, val body: String)

    private fun fetch(url: String): Page {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            c.setRequestProperty("Accept-Language", "uk,en;q=0.8")
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val bytes = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < 2_000_000) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            } ?: ByteArray(0)
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
            val body = runCatching { String(bytes, java.nio.charset.Charset.forName(charset ?: "UTF-8")) }.getOrElse { String(bytes) }
            return Page(code, body)
        } finally {
            c.disconnect()
        }
    }

    /** Rough visible text of an HTML page: drops scripts, styles and tags, decodes common entities. */
    fun visibleText(html: String): String =
        html.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace(Regex("\\s+"), " ").trim()

    /** First number near [hint] (a phrase, or a regex with an optional group), else the first number on the page. */
    fun findNumber(text: String, hint: String): Double? {
        val num = Regex("\\d[\\d\\s\\u00A0\\u202F.,']*\\d|\\d")
        val h = hint.trim()
        if (h.isNotEmpty()) {
            // Regex hint (only if it looks like one and compiles).
            if (h.any { it in "\\[(*+?" }) {
                val re = runCatching { Regex(h, RegexOption.IGNORE_CASE) }.getOrNull()
                val m = re?.find(text)
                if (m != null) {
                    val g = m.groups.drop(1).firstOrNull { it != null }?.value ?: m.value
                    num.find(g)?.let { return parseNumber(it.value) }
                }
            }
            val i = text.indexOf(h, ignoreCase = true)
            if (i < 0) return null
            val after = text.substring(i + h.length, minOf(text.length, i + h.length + 200))
            num.find(after)?.let { return parseNumber(it.value) }
            val before = text.substring(maxOf(0, i - 60), i)
            return num.findAll(before).lastOrNull()?.let { parseNumber(it.value) }
        }
        return num.find(text)?.let { parseNumber(it.value) }
    }

    /** "1 299,99" / "1,299.99" / "1.299,99" / "12'500" -> number. */
    fun parseNumber(raw: String): Double? {
        var s = raw.replace(Regex("[\\s\\u00A0\\u202F']"), "")
        val lastComma = s.lastIndexOf(','); val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> if (s.count { it == ',' } == 1 && s.length - lastComma - 1 in 1..2) s.replace(',', '.') else s.replace(",", "")
            lastDot >= 0 -> if (s.count { it == '.' } > 1 || (s.length - lastDot - 1 == 3 && lastDot <= 3 && s[0] != '0')) s.replace(".", "") else s
            else -> s
        }
        return s.toDoubleOrNull()
    }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    // ---------------------------------------------------------------- worker entry

    /** Called by [WatchWorker]. Blocking. */
    fun runCheck(ctx: Context, id: Int) {
        val w = readAll(ctx).firstOrNull { it.id == id } ?: run {
            runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }; return
        }
        val now = System.currentTimeMillis()
        // Back off after errors: skip runs until interval * 2^fails has passed (max 8x).
        if (w.fails > 0) {
            val wait = w.intervalMin * 60_000L * (1L shl (w.fails - 1).coerceAtMost(3))
            if (now - w.lastCheck < wait - 60_000L) return
        }
        val r = check(w)
        val fails = if (r.error) w.fails + 1 else 0
        val prev = w.lastState
        val updated = w.copy(lastCheck = now, lastResult = r.summary, lastHash = if (r.hash.isNotBlank()) r.hash else w.lastHash,
            lastState = r.state ?: prev, fails = fails)
        val fire = r.state == true && (!w.repeat || prev != true)
        if (fire) {
            val what = describe(w) + ": " + r.summary
            EventLog.add(ctx, "watch", "Watcher \"${w.label}\" fired: $what")
            Store.addMessage("note", "Watcher \"${w.label}\": $what (${w.target})")
            if (w.repeat) put(ctx, updated) else remove(ctx, w.id)
            val call = CallInfo(Store.nextId(), "watch", w.label, "", "$what. Address: ${w.target}")
            Store.putCall(call)
            Caller.ring(ctx, call)
            return
        }
        if (fails >= 10) {
            remove(ctx, w.id)
            Store.addMessage("note", "Watcher \"${w.label}\" stopped after 10 failed checks (${r.summary}).")
            EventLog.add(ctx, "watch", "Watcher \"${w.label}\" stopped after repeated errors: ${r.summary}")
            return
        }
        put(ctx, updated)
    }
}

class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("id", -1)
        if (id >= 0) withContext(Dispatchers.IO) { runCatching { Watchers.runCheck(applicationContext, id) } }
        return Result.success() // our own backoff handles errors; never let WorkManager retry-storm a site
    }
}
