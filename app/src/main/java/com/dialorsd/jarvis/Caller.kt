package com.dialorsd.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/** "Jarvis is calling": a high-priority call-style notification with a full-screen intent. */
object Caller {
    const val CH_CALL = "jarvis_calls"
    const val CH_WORK = "jarvis_work"
    const val EXTRA_CALL = "call_id"
    const val ACTION_RING = "com.dialorsd.jarvis.RING"
    const val ACTION_ANSWER = "com.dialorsd.jarvis.ANSWER"
    const val ACTION_TIMEOUT = "com.dialorsd.jarvis.CALL_TIMEOUT"
    const val ACTION_NAG = "com.dialorsd.jarvis.CALL_NAG"
    const val ACTION_DONE = "com.dialorsd.jarvis.CALL_DONE"
    const val EXTRA_ATTEMPT = "attempt"
    /** How long the phone rings before the call counts as missed. */
    const val RING_MS = 60_000L
    private const val NOTIF_BASE = 10_000
    private val VIBRATION = longArrayOf(0, 800, 600, 800, 600, 800, 1500)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val call = NotificationChannel(CH_CALL, "Calls from Jarvis", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when a timer ends or a task is finished"
            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(tone, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val work = NotificationChannel(CH_WORK, "Jarvis working", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Jarvis works on a task in the background"
        }
        nm.createNotificationChannel(call)
        nm.createNotificationChannel(work)
    }

    fun canNotify(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun canFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
    }

    fun callIntent(ctx: Context, callId: Int, action: String): Intent =
        Intent(ctx, CallActivity::class.java).setAction(action).putExtra(EXTRA_CALL, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    fun ring(ctx: Context, call: CallInfo) {
        createChannels(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val ringPi = PendingIntent.getActivity(ctx, call.id * 10 + 1, callIntent(ctx, call.id, ACTION_RING), flags)
        val answerPi = PendingIntent.getActivity(ctx, call.id * 10 + 2, callIntent(ctx, call.id, ACTION_ANSWER), flags)
        val declinePi = PendingIntent.getBroadcast(ctx, call.id * 10 + 3,
            Intent(ctx, DeclineReceiver::class.java).putExtra(EXTRA_CALL, call.id), flags)

        val jarvis = Person.Builder().setName("Jarvis").setImportant(true).build()
        val text = describe(call)
        val b = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (call.kind == "pc") "PC: ${call.label}" else "Jarvis is calling")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(RING_MS)
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(jarvis, declinePi, answerPi))
        if (call.kind == "timer") {
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_jarvis, "Done", eventPi(ctx, call, ACTION_DONE, 6)).build())
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until answered/declined/timeout

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_BASE + call.id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the UI shows a warning about this.
        }
        // Counts as missed if nobody answers in time (the notification itself times out silently).
        Timers.wakeAt(ctx, System.currentTimeMillis() + RING_MS + 2_000, eventPi(ctx, call, ACTION_TIMEOUT, 4), alarmClock = false)
        // If the app is open on screen, show the call screen straight away.
        if (Store.appVisible) runCatching { ctx.startActivity(callIntent(ctx, call.id, ACTION_RING)) }
    }

    fun describe(call: CallInfo): String = when (call.kind) {
        "task" -> "Task finished: ${call.label}"
        "pc" -> "PC: ${call.label}"
        "morning" -> "Morning briefing"
        "test" -> "Test call"
        else -> (if (call.attempt > 0) "Reminder (again): " else "Timer finished: ") + call.label
    }

    /** Broadcast to [CallEventsReceiver]; request codes are unique per call and purpose. */
    fun eventPi(ctx: Context, call: CallInfo, action: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, call.id * 10 + slot,
            Intent(ctx, CallEventsReceiver::class.java).setAction(action)
                .putExtra(EXTRA_CALL, call.id).putExtra(EXTRA_ATTEMPT, call.attempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun stopRinging(ctx: Context, callId: Int) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_BASE + callId)
    }

    /** Stops nagging for a reminder: removes the call and any pending re-ring. */
    fun markDone(ctx: Context, callId: Int) {
        val c = Store.getCall(callId) ?: return
        stopRinging(ctx, callId)
        runCatching { ctx.getSystemService(android.app.AlarmManager::class.java)?.cancel(eventPi(ctx, c, ACTION_NAG, 5)) }
        Store.removeCall(callId)
        val uk = Store.lastLanguageIsUkrainian()
        Store.addMessage("note", if (uk) "Позначено як виконане: ${c.label}" else "Marked done: ${c.label}")
    }

    /**
     * Declined or not answered. Reminders (kind "timer") ring again later if nagging is on; the morning
     * briefing is delivered as a notification instead; everything else just leaves a note.
     */
    fun missed(ctx: Context, call: CallInfo, declined: Boolean) {
        stopRinging(ctx, call.id)
        val s = Settings(ctx)
        val uk = Store.lastLanguageIsUkrainian()
        if (call.kind == "timer" && s.nagOn && call.attempt < s.nagTimes) {
            val next = call.copy(attempt = call.attempt + 1)
            Store.putCall(next)
            Timers.wakeAt(ctx, System.currentTimeMillis() + s.nagMinutes * 60_000L, eventPi(ctx, next, ACTION_NAG, 5))
            Store.addMessage("note", (if (declined) (if (uk) "Дзвінок відхилено" else "Call declined") else (if (uk) "Без відповіді" else "No answer")) +
                " (${call.label}). " + (if (uk) "Передзвоню через ${s.nagMinutes} хв." else "I'll ring again in ${s.nagMinutes} min."))
            return
        }
        Store.removeCall(call.id)
        when (call.kind) {
            "morning" -> Morning.deliverAsNotification(ctx)
            else -> {
                val isTask = call.kind == "task"
                val what = if (declined) (if (uk) "Ви відхилили дзвінок" else "Call declined") else (if (uk) "Пропущений дзвінок" else "Missed call")
                Store.addMessage("note", "$what (${call.label})." + if (isTask) (if (uk) " Результат збережено вище." else " The result is saved above.") else "")
            }
        }
    }
}

/** Ring timeout, re-ring (nag) and "Done" from the notification. */
class CallEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Caller.EXTRA_CALL, -1)
        val attempt = intent.getIntExtra(Caller.EXTRA_ATTEMPT, 0)
        val call = Store.getCall(id) ?: return // answered, declined or done already
        when (intent.action) {
            Caller.ACTION_TIMEOUT -> if (call.attempt == attempt) {
                // Morning briefing needs network: keep the receiver alive while it is built.
                val pending = goAsync()
                Thread {
                    try { Caller.missed(context, call, declined = false) } finally { pending.finish() }
                }.start()
            }
            Caller.ACTION_NAG -> Caller.ring(context, call)
            Caller.ACTION_DONE -> Caller.markDone(context, id)
        }
    }
}

/** "Decline" button on the call notification. */
class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Caller.EXTRA_CALL, -1)
        if (id < 0) return
        Caller.stopRinging(context, id)
        val pending = goAsync()
        Thread { try { declineCall(context, id) } finally { pending.finish() } }.start()
    }
}

/** Blocking for the morning briefing (network): call off the main thread. */
fun declineCall(ctx: Context, id: Int) {
    val c = Store.getCall(id) ?: return
    Caller.missed(ctx.applicationContext, c, declined = true)
}
