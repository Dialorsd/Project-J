package com.dialorsd.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Countdown timers backed by AlarmManager; persisted in [Store] and re-scheduled after reboot. */
object Timers {
    const val EXTRA_ID = "timer_id"

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun add(ctx: Context, seconds: Long, label: String): TimerItem {
        val secs = seconds.coerceIn(1, 7L * 24 * 3600)
        val t = TimerItem(Store.nextId(), label.ifBlank { formatDuration(secs) }, System.currentTimeMillis() + secs * 1000)
        Store.putTimer(t)
        schedule(ctx, t)
        return t
    }

    /** A reminder at an absolute time (epoch ms); rings like a timer. */
    fun addAt(ctx: Context, endAt: Long, label: String): TimerItem {
        val t = TimerItem(Store.nextId(), label.ifBlank { "Reminder" }.take(120), endAt.coerceAtMost(System.currentTimeMillis() + 366L * 86_400_000))
        Store.putTimer(t)
        schedule(ctx, t)
        return t
    }

    /** Wakes the phone at [at] with [pi]: alarm-clock alarm if exact alarms are allowed, else best effort. */
    fun wakeAt(ctx: Context, at: Long, pi: PendingIntent, alarmClock: Boolean = true) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (canExact(ctx)) {
                if (alarmClock) {
                    val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, id, Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, t: TimerItem) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, t.id)
        try {
            if (canExact(ctx)) {
                val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(t.endAt, show), pi)
            } else {
                // Without the exact-alarm permission the timer may ring a few minutes late.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, pi)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, id))
        Store.removeTimer(id)
        TaskService.cancelled.add(id)
    }

    /** Cancels timers whose label matches; "all" cancels every timer. Returns how many were cancelled. */
    fun cancelByLabel(ctx: Context, label: String): Int {
        val l = label.trim().lowercase()
        val all = l.isEmpty() || l == "all" || l == "всі" || l == "усі"
        val hits = Store.loadTimers().filter { it.kind == "timer" && (all || it.label.lowercase().contains(l) || l.contains(it.label.lowercase())) }
        hits.forEach { cancel(ctx, it.id) }
        return hits.size
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        Store.loadTimers().filter { it.kind == "timer" }.forEach { t ->
            if (t.endAt <= now) schedule(ctx, t.copy(endAt = now + 3000)) else schedule(ctx, t)
        }
        // Tasks cannot resume after a reboot/process death in v1: drop them.
        Store.loadTimers().filter { it.kind == "task" }.forEach { Store.removeTimer(it.id) }
    }

    fun formatDuration(secs: Long): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            else -> "%d:%02d".format(m, s)
        }
    }
}

/** Fires when a timer ends: Jarvis "phones" the user. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Timers.EXTRA_ID, -1)
        if (id < 0) return
        val t = Store.removeTimer(id) ?: return // already cancelled
        val uk = Store.lastLanguageIsUkrainian()
        Store.addMessage("note", if (uk) "Таймер «${t.label}» завершився." else "Timer \"${t.label}\" finished.")
        EventLog.add(context, "timer", "Timer/reminder \"${t.label}\" rang")
        val call = CallInfo(id, "timer", t.label, "")
        Store.putCall(call)
        Caller.ring(context, call)
    }
}

/** Re-schedules timers after reboot, app update, or when the exact-alarm permission changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Timers.rescheduleAll(context)
        }
        // v3: morning call and calendar heads-ups are alarms too; re-arm them (cheap, no network).
        runCatching { Morning.schedule(context) }
        runCatching { Proactive.scheduleEvents(context) }
        runCatching { Proactive.scheduleBatteryCheck(context) }
        runCatching { Wrap.schedule(context) } // v4 (watchers are WorkManager jobs, which survive reboots by themselves)
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) && Settings(context).pcLink) {
            PcLinkService.start(context)
        }
    }
}
