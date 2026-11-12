package com.dialorsd.jarvis

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** role: "user", "jarvis", "note" or "sources" (lines of "title\turl"). */
data class Msg(val role: String, val text: String, val time: Long = System.currentTimeMillis())

/** kind: "timer" (endAt is when it rings) or "task" (endAt = 0, still running). */
data class TimerItem(val id: Int, val label: String, val endAt: Long, val kind: String = "timer")

/**
 * A pending "phone call" from Jarvis. line = what he says on pick-up (empty = generate it).
 * kind: "timer", "task", "pc", "morning" or "test". attempt counts re-rings (nagging), starting at 0.
 */
data class CallInfo(val id: Int, val kind: String, val label: String, val line: String, val detail: String = "",
                    val attempt: Int = 0, val keep: Int = 0)

/**
 * Tiny app-wide state + persistence (SharedPreferences JSON). Compose observes the state lists.
 * All mutations are posted to the main thread.
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    val messages = mutableStateListOf<Msg>()
    val timers = mutableStateListOf<TimerItem>()
    val orb = mutableStateOf(OrbState.IDLE)
    val partialSpeech = mutableStateOf("")
    @Volatile var appVisible = false
    private var appCtx: Context? = null

    fun init(app: Application) {
        if (::prefs.isInitialized) return
        appCtx = app
        prefs = app.getSharedPreferences("jarvis_store", Context.MODE_PRIVATE)
        messages.addAll(loadMessages())
        timers.addAll(loadTimers().filter { it.kind == "timer" }) // tasks do not survive process death in v1
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun setOrb(s: OrbState) = onMain { orb.value = s }

    /** v4: lets other state holders (watchers...) update Compose state on the main thread. */
    fun runOnMain(block: () -> Unit) = onMain(block)

    // ---- transcript ----
    fun addMessage(role: String, text: String) = onMain {
        messages.add(Msg(role, text))
        while (messages.size > 200) messages.removeAt(0)
        saveMessages()
    }

    fun addSources(sources: List<Pair<String, String>>) {
        if (sources.isNotEmpty()) addMessage("sources", sources.joinToString("\n") { "${it.first.replace('\t', ' ')}\t${it.second}" })
    }

    fun clearMessages() = onMain { messages.clear(); saveMessages() }

    private fun saveMessages() {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text).put("at", it.time)) }
        prefs.edit().putString("messages", arr.toString()).apply()
    }

    private fun loadMessages(): List<Msg> = runCatching {
        val arr = JSONArray(prefs.getString("messages", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Msg(o.getString("r"), o.getString("t"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    // ---- timers / tasks ----
    @Synchronized
    fun nextId(): Int {
        val id = prefs.getInt("next_id", 1)
        prefs.edit().putInt("next_id", if (id >= 100_000) 1 else id + 1).apply()
        return id
    }

    /** Persisted copy, readable from any thread (receivers run before the UI exists). */
    @Synchronized
    fun loadTimers(): List<TimerItem> = runCatching {
        val arr = JSONArray(prefs.getString("timers", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TimerItem(o.getInt("id"), o.getString("label"), o.getLong("end"), o.optString("kind", "timer"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun saveTimers(list: List<TimerItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("end", it.endAt).put("kind", it.kind)) }
        prefs.edit().putString("timers", arr.toString()).commit()
    }

    @Synchronized
    fun putTimer(t: TimerItem) {
        saveTimers(loadTimers().filter { it.id != t.id } + t)
        onMain { timers.removeAll { it.id == t.id }; timers.add(t) }
        appCtx?.let { c -> runCatching { JarvisWidget.updateAll(c) } }
    }

    @Synchronized
    fun removeTimer(id: Int): TimerItem? {
        val all = loadTimers()
        val t = all.firstOrNull { it.id == id }
        saveTimers(all.filter { it.id != id })
        onMain { timers.removeAll { it.id == id } }
        appCtx?.let { c -> runCatching { JarvisWidget.updateAll(c) } }
        return t
    }

    // ---- calls ----
    @Synchronized
    fun putCall(c: CallInfo) {
        val o = JSONObject(prefs.getString("calls", "{}") ?: "{}")
        o.put(c.id.toString(), JSONObject().put("kind", c.kind).put("label", c.label).put("line", c.line).put("detail", c.detail).put("attempt", c.attempt).put("keep", c.keep))
        prefs.edit().putString("calls", o.toString()).commit()
    }

    @Synchronized
    fun getCall(id: Int): CallInfo? = runCatching {
        val o = JSONObject(prefs.getString("calls", "{}") ?: "{}").optJSONObject(id.toString()) ?: return null
        CallInfo(id, o.optString("kind", "timer"), o.optString("label"), o.optString("line"), o.optString("detail"),
            o.optInt("attempt", 0), o.optInt("keep", 0))
    }.getOrNull()

    @Synchronized
    fun removeCall(id: Int) {
        val o = JSONObject(prefs.getString("calls", "{}") ?: "{}")
        o.remove(id.toString())
        prefs.edit().putString("calls", o.toString()).apply()
    }

    /** Transcript notes from the PC link received after [since] (epoch ms). */
    fun pcPingsSince(since: Long): List<String> =
        messages.toList().filter { it.role == "note" && it.text.startsWith("PC: ") && it.time >= since }.map { it.text.removePrefix("PC: ") }

    /** Language Jarvis should use when he starts the conversation (calls). */
    fun lastLanguageIsUkrainian(): Boolean =
        messages.lastOrNull { it.role == "user" }?.let { hasCyrillic(it.text) } ?: false
}
