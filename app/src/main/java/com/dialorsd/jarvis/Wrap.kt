package com.dialorsd.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * v4: a tiny on-device log of things that happened (timer rang, task finished, PC ping, watcher fired...),
 * capped at 200 entries and 7 days. Used by the daily wrap-up and "what happened today?".
 */
object EventLog {
    private const val MAX = 200
    private const val KEEP_MS = 7 * 86_400_000L

    data class Entry(val at: Long, val kind: String, val text: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis_events", Context.MODE_PRIVATE)

    /** Never throws: logging must not break the feature that calls it. */
    @Synchronized
    fun add(ctx: Context, kind: String, text: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val list = load(ctx).filter { now - it.at < KEEP_MS }.toMutableList()
            list += Entry(now, kind, text.take(300))
            val arr = JSONArray()
            list.takeLast(MAX).forEach { arr.put(JSONObject().put("at", it.at).put("k", it.kind).put("t", it.text)) }
            prefs(ctx).edit().putString("events", arr.toString()).apply()
        }
    }

    @Synchronized
    fun load(ctx: Context): List<Entry> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("events", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Entry(it.optLong("at"), it.optString("k"), it.optString("t")) }
        }
    }.getOrDefault(emptyList())

    fun since(ctx: Context, from: Long): List<Entry> = load(ctx).filter { it.at >= from }
}

/** Daily wrap-up: a notification (optionally a call) in the evening with what happened and what is next. */
object Wrap {
    private const val REQ = 900_301
    private const val NOTIF = 900_302
    private const val CH = "jarvis_wrap"
    const val ACTION_WRAP = "com.dialorsd.jarvis.WRAP"

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, WrapReceiver::class.java).setAction(ACTION_WRAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** (Re)arms or cancels the daily alarm. Cheap. */
    fun schedule(ctx: Context) {
        val s = Settings(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!s.wrapOn) { am.cancel(pi(ctx)); return }
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(LocalTime.of(s.wrapTime / 60, s.wrapTime % 60))
        if (!at.isAfter(now.plusSeconds(30))) at = at.plusDays(1)
        Timers.wakeAt(ctx, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi(ctx), alarmClock = false)
    }

    /** Starts building the wrap-up in WorkManager (network may take a while). */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<WrapWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    /** Facts for the brain: today's events log, running things, open list items, tomorrow. Blocking (calendar, weather). */
    fun facts(ctx: Context): String {
        val s = Settings(ctx)
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("Now: ").append(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())).append('\n')
        val log = EventLog.since(ctx, todayStart)
        sb.append("What happened today (from the phone's log):\n")
        if (log.isEmpty()) sb.append("- nothing logged\n")
        log.takeLast(40).forEach { sb.append("- ").append(tf.format(Date(it.at))).append(" [").append(it.kind).append("] ").append(it.text).append('\n') }
        val timers = Store.loadTimers()
        sb.append("Still running: ").append(if (timers.isEmpty()) "no timers or tasks" else timers.joinToString("; ") {
            if (it.kind == "task") "task ${it.label}" else "${it.label} at ${SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(it.endAt))}"
        }).append('\n')
        val w = Watchers.list(ctx)
        sb.append("Active watchers: ").append(if (w.isEmpty()) "none" else w.joinToString("; ") { "${it.label} (${it.kind})" }).append('\n')
        sb.append(Notes.forPrompt(800).ifBlank { "Open notes and list items: none." }).append('\n')
        val tStart = todayStart + 86_400_000L
        val ev = if (!DeviceCalendar.canRead(ctx)) "calendar not allowed" else
            DeviceCalendar.instances(ctx, tStart, tStart + 86_400_000L).joinToString("; ") {
                (if (it.allDay) "all day" else tf.format(Date(it.begin))) + " " + it.title
            }.ifBlank { "none" }
        sb.append("Tomorrow's events: ").append(ev).append('\n')
        val wt = runCatching { Weather.tomorrow(s) }
        sb.append("Tomorrow's weather: ").append(wt.getOrNull() ?: if (s.city.isBlank()) "no city set" else "unavailable").append('\n')
        return sb.toString()
    }

    /** The spoken/written wrap-up: brain first, plain facts as the fallback. Blocking (network). */
    fun summaryText(ctx: Context, uk: Boolean): String {
        val s = Settings(ctx)
        val f = facts(ctx)
        val lang = if (uk) "Ukrainian" else "English"
        val sys = Brain.character(s) + Brain.memoryBlock() + """

It is the evening and you are giving the user a short wrap-up of his day. Facts gathered by the phone:
$f

Write it in $lang as you would say it: what got done or happened today, what is still running, open list items worth
mentioning, and tomorrow's events and weather, skipping anything empty. At most about 8 short sentences, no lists or markdown.
Output ONLY a JSON object: {"say": "..."}"""
        val said = runCatching { Brain.parseReply(Brain.generate(s, sys, listOf("user" to "(Wrap-up time.)"), json = true)).say }.getOrNull()
        return said?.takeIf { it.isNotBlank() } ?: ((if (uk) "Підсумок дня, сер. " else "Your evening summary, sir. ") + f.replace('\n', ' '))
    }

    fun deliverAsNotification(ctx: Context) {
        val uk = Store.lastLanguageIsUkrainian()
        val text = summaryText(ctx, uk)
        Store.addMessage("jarvis", text)
        notify(ctx, if (uk) "Підсумок дня" else "Evening wrap-up", text)
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "Daily wrap-up", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(NOTIF, n) }
    }
}

/** Alarm at the wrap-up time: hand the work to WorkManager, then arm tomorrow's alarm. */
class WrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Wrap.ACTION_WRAP) return
        if (Settings(context).wrapOn) Wrap.runNow(context)
        Wrap.schedule(context)
    }
}

class WrapWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Settings(ctx).wrapCall) withContext(Dispatchers.IO) {
            val call = CallInfo(Store.nextId(), "wrap", "Evening wrap-up", "")
            Store.putCall(call)
            Caller.ring(ctx, call)
        } else withContext(Dispatchers.IO) {
            Wrap.deliverAsNotification(ctx)
        }
        return Result.success()
    }

    /** Only used on Android 11 and older, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Caller.createChannels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Caller.CH_WORK).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is preparing your wrap-up").setOngoing(true).build()
        return ForegroundInfo(NOTIF_FG, n)
    }

    companion object { private const val NOTIF_FG = 900_303 }
}
