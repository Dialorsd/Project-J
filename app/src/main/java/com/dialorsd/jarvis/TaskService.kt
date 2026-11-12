package com.dialorsd.jarvis

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs "task" actions (a second Gemini call) in a foreground service so they finish while the app
 * is in the background, then phones the user with the result.
 */
class TaskService : Service() {

    companion object {
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_LABEL = "label"
        private const val NOTIF_ID = 42
        val cancelled: MutableSet<Int> = Collections.synchronizedSet(HashSet())

        fun start(ctx: Context, prompt: String, label: String) {
            val i = Intent(ctx, TaskService::class.java)
                .putExtra(EXTRA_PROMPT, prompt).putExtra(EXTRA_LABEL, label.ifBlank { "task" })
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Caller.createChannels(this)
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "task"
        val notif = NotificationCompat.Builder(this, Caller.CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is working")
            .setContentText(label)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            // Could not go foreground (e.g. background start restrictions); still try to run the task.
        }
        val prompt = intent?.getStringExtra(EXTRA_PROMPT)
        if (prompt.isNullOrBlank()) {
            if (running.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        running.incrementAndGet()
        val item = TimerItem(Store.nextId(), label, 0L, "task")
        Store.putTimer(item)
        Thread {
            try {
                runTask(item, prompt)
            } finally {
                if (running.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun runTask(item: TimerItem, prompt: String) {
        val s = Settings(this)
        val uk = hasCyrillic(prompt)
        val line: String = try {
            val (summary, result, sources) = Brain.task(s, prompt)
            if (item.id in cancelled) return
            Store.addMessage("jarvis", (if (uk) "Результат завдання «${item.label}»:\n" else "Task result \"${item.label}\":\n") + result)
            Store.addSources(sources)
            summary
        } catch (e: Exception) {
            if (item.id in cancelled) return
            val err = Brain.errorLine(e, uk)
            Store.addMessage("jarvis", err)
            err
        } finally {
            Store.removeTimer(item.id)
        }
        val call = CallInfo(item.id, "task", item.label, line)
        Store.putCall(call)
        Caller.ring(this, call)
    }
}
