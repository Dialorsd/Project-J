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
        val text = if (call.kind == "task") "Task finished: ${call.label}" else "Timer finished: ${call.label}"
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is calling")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000)
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(jarvis, declinePi, answerPi))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until answered/declined/timeout

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_BASE + call.id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the UI shows a warning about this.
        }
        // If the app is open on screen, show the call screen straight away.
        if (Store.appVisible) runCatching { ctx.startActivity(callIntent(ctx, call.id, ACTION_RING)) }
    }

    fun stopRinging(ctx: Context, callId: Int) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_BASE + callId)
    }
}

/** "Decline" button on the call notification. */
class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Caller.EXTRA_CALL, -1)
        if (id < 0) return
        Caller.stopRinging(context, id)
        declineCall(id, Store.lastLanguageIsUkrainian())
    }
}

fun declineCall(id: Int, uk: Boolean) {
    val c = Store.getCall(id) ?: return
    Store.removeCall(id)
    val isTask = c.kind == "task"
    val note = if (uk) {
        "Ви відхилили дзвінок (${c.label})." + (if (isTask) " Результат збережено вище." else "")
    } else {
        "Call declined (${c.label})." + (if (isTask) " The result is saved above." else "")
    }
    Store.addMessage("note", note)
}
