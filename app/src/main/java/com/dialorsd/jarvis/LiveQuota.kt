package com.dialorsd.jarvis

import org.json.JSONObject

/** What LiveService runs: a Gemini Live session or the hands-free loop. */
interface Conversation {
    val interpreter: Boolean
    val handsFree: Boolean
    var muted: Boolean
    fun begin()
    fun end(message: String?)
    fun sendUserText(text: String): Boolean
    /** Muting changed from the UI / headset (hands-free stops or restarts listening). */
    fun onMuteChanged() {}
}

/** Today's Live usage counters (reset at midnight Pacific, when Google's daily quotas reset). */
object LiveUsage {
    data class Day(val day: String, val sentMs: Long, val sessionMs: Long, val sessions: Int, val model: String,
                   val err: String, val errAt: Long, val errModel: String)

    @Synchronized private fun load(s: Settings): JSONObject {
        val o = runCatching { JSONObject(s.liveUsage) }.getOrDefault(JSONObject())
        val today = QuotaClock.pacificDay(System.currentTimeMillis())
        if (o.optString("day") != today) {
            o.put("day", today).put("sent", 0L).put("time", 0L).put("sessions", 0)
        }
        return o
    }

    @Synchronized fun addTime(s: Settings, sentMs: Long, sessionMs: Long) {
        if (sentMs <= 0 && sessionMs <= 0) return
        val o = load(s)
        o.put("sent", o.optLong("sent") + sentMs).put("time", o.optLong("time") + sessionMs)
        s.liveUsage = o.toString()
    }

    @Synchronized fun session(s: Settings, model: String) {
        val o = load(s)
        o.put("sessions", o.optInt("sessions") + 1).put("model", model)
        s.liveUsage = o.toString()
    }

    @Synchronized fun error(s: Settings, model: String, text: String) {
        val o = load(s)
        o.put("err", text.take(400)).put("errAt", System.currentTimeMillis()).put("errModel", model)
        s.liveUsage = o.toString()
    }

    fun today(s: Settings): Day {
        val o = load(s)
        return Day(o.optString("day"), o.optLong("sent"), o.optLong("time"), o.optInt("sessions"), o.optString("model"),
            o.optString("err"), o.optLong("errAt"), o.optString("errModel"))
    }

    fun fmt(ms: Long): String {
        val sec = ms / 1000
        return if (sec >= 3600) "%dh %02dm".format(sec / 3600, sec / 60 % 60) else "%dm %02ds".format(sec / 60, sec % 60)
    }
}

/** Live model rotation: which models may be tried now, and remembering the ones out of quota. */
object LiveModels {
    /** Google says the model is closed to new keys: retrying tomorrow will not help. */
    fun isPermanent(reason: String): Boolean {
        val r = reason.lowercase()
        return "new users" in r || "no longer available" in r || "has been deprecated" in r || "was shut down" in r
    }

    /** Models in order that are not currently skipped. */
    fun available(s: Settings): List<String> = s.liveModels.filter { s.liveSkipReason(it) == null }

    /** Out of quota until the daily reset (or until RETRY when Google closed it for good). */
    fun markExhausted(s: Settings, model: String, why: String) {
        val permanent = isPermanent(why)
        s.skipLiveModel(model, (if (permanent) "not available: " else "out of quota: ") + why,
            if (permanent) Long.MAX_VALUE else QuotaClock.nextPacificMidnight(System.currentTimeMillis()))
    }

    /** Refused (unknown model name, not supported...): skip until the daily reset. */
    fun markRefused(s: Settings, model: String, why: String) {
        s.skipLiveModel(model, "refused: $why", if (isPermanent(why)) Long.MAX_VALUE
            else QuotaClock.nextPacificMidnight(System.currentTimeMillis()))
    }

    /** One line per model for Settings. */
    fun status(s: Settings): List<Pair<String, String>> = s.liveModels.map { m ->
        val why = s.liveSkipReason(m)
        val until = s.liveSkipUntil(m)
        m to when {
            why == null -> "ready"
            until == Long.MAX_VALUE -> "skipped until RETRY ($why)"
            else -> "skipped until " + java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(until)) + " ($why)"
        }
    }
}
