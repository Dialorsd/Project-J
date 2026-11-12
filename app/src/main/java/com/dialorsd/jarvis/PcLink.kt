package com.dialorsd.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "PC link": keeps a streaming subscription to an ntfy topic (https://ntfy.sh/<topic>/json).
 * Every message from the PC makes Jarvis "call" the user; low priority (1-2) or the tag "quiet"
 * only posts a normal notification.
 */
class PcLinkService : Service() {

    companion object {
        private const val NOTIF_ID = 43
        const val CH_LINK = "jarvis_pc_link"
        const val CH_PC_INFO = "jarvis_pc_info"
        /** Unix seconds; never replay messages older than this window after being offline. */
        private const val MAX_BACKLOG_S = 12 * 3600L

        val status = mutableStateOf("off")

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PcLinkService::class.java))
            } catch (e: Exception) {
                // Background start not allowed right now (Android 12+); it starts next time the app opens.
                status.value = "waiting for app to open"
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PcLinkService::class.java))
            status.value = "off"
        }
    }

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CH_LINK, "PC link (always on)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Jarvis listens for messages from your PC"
        })
        nm?.createNotificationChannel(NotificationChannel(CH_PC_INFO, "Quiet PC messages", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is listening for your PC")
            .setContentText("Turn off in Jarvis > Settings > PC link")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) {
            status.value = "could not start: ${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings(this).pcLink) { stopSelf(); return START_NOT_STICKY }
        if (thread?.isAlive != true) {
            running = true
            thread = Thread({ loop() }, "pc-link").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { conn?.disconnect() }
        thread?.interrupt()
        status.value = "off"
        super.onDestroy()
    }

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            val s = Settings(this)
            if (!s.pcLink) break
            try {
                status.value = "connecting"
                subscribe(s) { backoff = 2_000L }
            } catch (e: Exception) {
                if (!running) break
                status.value = "offline, retrying (${e.javaClass.simpleName})"
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(300_000L)
        }
    }

    /** One streaming connection; returns when it closes, throws on errors. */
    private fun subscribe(s: Settings, onOpen: () -> Unit) {
        val now = System.currentTimeMillis() / 1000
        val since = s.ntfySince.let { if (it <= 0L) now else it.coerceAtLeast(now - MAX_BACKLOG_S) }
        val topic = URLEncoder.encode(s.ntfyTopic, "UTF-8")
        val c = URL("${s.ntfyServer}/$topic/json?since=$since").openConnection() as HttpURLConnection
        conn = c
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 120_000 // ntfy sends keepalive events well within this
            c.setRequestProperty("Accept", "application/x-ndjson")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (o.optString("event")) {
                        "open" -> { status.value = "connected"; onOpen(); if (s.ntfySince <= 0L) s.ntfySince = now }
                        "keepalive" -> s.ntfySince = maxOf(s.ntfySince, o.optLong("time", now) - 30)
                        "message" -> handle(s, o)
                    }
                }
            }
        } finally {
            conn = null
            c.disconnect()
        }
    }

    private fun handle(s: Settings, o: JSONObject) {
        val id = o.optString("id")
        val seen = s.ntfySeen.split(',').filter { it.isNotBlank() }
        if (id.isNotBlank() && id in seen) return
        s.ntfySeen = (seen + id).takeLast(50).joinToString(",")
        s.ntfySince = maxOf(s.ntfySince, o.optLong("time", 0L))

        val message = o.optString("message").trim().take(1000)
        val title = o.optString("title").trim().take(120)
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } } ?: emptyList()
        val priority = o.optInt("priority", 3)
        val quiet = priority <= 2 || "quiet" in tags || "silent" in tags
        val label = title.ifBlank { "PC" }

        Store.addMessage("note", "PC: " + (if (title.isNotBlank()) "$title: " else "") + message)
        if (quiet) {
            val n = NotificationCompat.Builder(this, CH_PC_INFO)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("PC: $label").setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true).build()
            runCatching { getSystemService(NotificationManager::class.java)?.notify(20_000 + Store.nextId(), n) }
            return
        }
        val call = CallInfo(Store.nextId(), "pc", label, "", message)
        Store.putCall(call)
        Caller.ring(this, call)
    }
}
