package com.dialorsd.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proactive remarks, all alarm/broadcast driven (no polling loops):
 * - calendar heads-up N minutes before an event (one alarm for the next event, re-armed after each),
 * - low battery (system ACTION_BATTERY_LOW while the app process is alive, plus a non-waking
 *   half-hourly check that only runs when the phone is awake anyway).
 */
object Proactive {
    private const val CH = "jarvis_heads_up"
    private const val REQ_EVENT = 900_101
    private const val REQ_BATTERY = 900_102
    private const val NOTIF_EVENT = 900_201
    private const val NOTIF_BATTERY = 900_202
    const val ACTION_EVENT = "com.dialorsd.jarvis.EVENT_CHECK"
    const val ACTION_BATTERY = "com.dialorsd.jarvis.BATTERY_CHECK"

    private fun pi(ctx: Context, action: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, ProactiveReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun channel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(NotificationChannel(CH, "Heads-ups from Jarvis", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Upcoming events and low battery"
        })
        return nm
    }

    // ---------- calendar ----------

    /** Arms one alarm for the next event heads-up (or a re-check in 6 hours). Blocking calendar query is small. */
    fun scheduleEvents(ctx: Context) {
        val s = Settings(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, ACTION_EVENT, REQ_EVENT)
        if (!s.eventAlerts || !DeviceCalendar.canRead(ctx)) { am.cancel(p); return }
        val now = System.currentTimeMillis()
        val lead = s.eventMinutes * 60_000L
        val done = s.eventNotified.split('|').toSet()
        val next = DeviceCalendar.instances(ctx, now, now + 2 * 86_400_000L)
            .filter { !it.allDay && it.begin > now && key(it) !in done }
            .minByOrNull { it.begin }
        val recheck = now + 6 * 3600_000L
        val at = next?.let { maxOf(now + 5_000, it.begin - lead) } ?: recheck
        Timers.wakeAt(ctx, minOf(at, recheck), p, alarmClock = false)
    }

    private fun key(i: DeviceCalendar.Instance) = "${i.begin}:${i.title.hashCode()}"

    fun onEventAlarm(ctx: Context) {
        val s = Settings(ctx)
        if (!s.eventAlerts) return
        val now = System.currentTimeMillis()
        val lead = s.eventMinutes * 60_000L
        val done = s.eventNotified.split('|').filter { it.isNotBlank() }.toMutableList()
        val due = DeviceCalendar.instances(ctx, now, now + lead + 60_000)
            .filter { !it.allDay && it.begin > now - 60_000 && key(it) !in done }
        val uk = Store.lastLanguageIsUkrainian()
        for (e in due) {
            done += key(e)
            val mins = ((e.begin - now + 30_000) / 60_000).coerceAtLeast(0)
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.begin))
            val text = if (uk) "Сер, «${e.title}» о $time, за $mins хв." + (if (e.location.isNotBlank()) " Місце: ${e.location}." else "")
                else "Sir, \"${e.title}\" begins at $time, in $mins minutes." + (if (e.location.isNotBlank()) " Location: ${e.location}." else "")
            notify(ctx, NOTIF_EVENT + (e.begin / 60_000 % 1000).toInt(), text)
            Store.addMessage("note", text)
            if (s.eventSpeak) say(ctx, text)
        }
        s.eventNotified = done.takeLast(30).joinToString("|")
        scheduleEvents(ctx)
    }

    /** Speaks only when it will be heard: in Live mode, or with the app on screen. */
    private fun say(ctx: Context, text: String) {
        if (LiveSession.active) { LiveSession.announce(text); return }
        if (Store.appVisible) Jarvis.scope.launch { Voice.speak(ctx.applicationContext, text) }
    }

    // ---------- battery ----------

    @Volatile private var registered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) Settings(context).batteryWarned = false
            else checkBattery(context)
        }
    }

    /** Registers for ACTION_BATTERY_LOW while the process lives (manifest receivers cannot get it since Android 8). */
    @Synchronized
    fun registerBatteryReceiver(ctx: Context) {
        val app = ctx.applicationContext
        val on = Settings(app).batteryAlerts
        if (on && !registered) {
            val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_POWER_CONNECTED) }
            runCatching { ContextCompat.registerReceiver(app, batteryReceiver, f, ContextCompat.RECEIVER_EXPORTED) }
                .onSuccess { registered = true }
        } else if (!on && registered) {
            runCatching { app.unregisterReceiver(batteryReceiver) }
            registered = false
        }
    }

    /** Half-hourly, NON-waking alarm: runs only when the phone is awake for some other reason. */
    fun scheduleBatteryCheck(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, ACTION_BATTERY, REQ_BATTERY)
        if (!Settings(ctx).batteryAlerts) { am.cancel(p); return }
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_HOUR,
            AlarmManager.INTERVAL_HALF_HOUR, p)
    }

    fun checkBattery(ctx: Context) {
        val s = Settings(ctx)
        if (!s.batteryAlerts) return
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level <= 0) return
        val charging = bm.isCharging
        if (charging || level > s.batteryLevel + 5) { s.batteryWarned = false; return }
        if (level <= s.batteryLevel && !s.batteryWarned) {
            s.batteryWarned = true
            val uk = Store.lastLanguageIsUkrainian()
            val text = if (uk) "Сер, заряд батареї $level%. Можливо, час на зарядку." else
                "Sir, the battery is down to $level%. Might I suggest the charger?"
            notify(ctx, NOTIF_BATTERY, text)
        }
    }

    private fun notify(ctx: Context, id: Int, text: String) {
        val nm = channel(ctx) ?: return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(id, n) }
    }
}

class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Proactive.ACTION_EVENT -> {
                val pending = goAsync()
                Thread { try { Proactive.onEventAlarm(context) } finally { pending.finish() } }.start()
            }
            Proactive.ACTION_BATTERY -> Proactive.checkBattery(context)
        }
    }
}
