package com.dialorsd.jarvis

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

// ------------------------------------------------------------------ memory

data class Memory(val id: Long, val text: String)

/** Long-term notes about the user, stored on the device and injected into the system prompt. */
object Memories {
    private const val MAX_ITEMS = 100
    private const val PROMPT_CHARS = 2500
    val items = mutableStateListOf<Memory>() // newest last

    @Synchronized
    fun load(ctx: Context) {
        if (items.isNotEmpty()) return
        val arr = runCatching { JSONArray(prefs(ctx).getString("memories", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(Memory(o.optLong("id"), o.optString("t")))
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)

    @Synchronized
    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.text)) }
        prefs(ctx).edit().putString("memories", arr.toString()).apply()
    }

    @Synchronized
    fun remember(ctx: Context, text: String) {
        val t = text.trim().take(400)
        if (t.isEmpty()) return
        items.removeAll { it.text.equals(t, ignoreCase = true) }
        items.add(Memory(System.currentTimeMillis(), t))
        while (items.size > MAX_ITEMS) items.removeAt(0)
        save(ctx)
    }

    /** Removes memories containing [match] (case-insensitive). Returns the number removed. */
    @Synchronized
    fun forget(ctx: Context, match: String): Int {
        val m = match.trim().lowercase()
        if (m.isEmpty()) return 0
        val before = items.size
        items.removeAll { it.text.lowercase().contains(m) }
        save(ctx)
        return before - items.size
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        items.removeAll { it.id == id }
        save(ctx)
    }

    /** Most recent first, capped in size. */
    @Synchronized
    fun forPrompt(): String {
        val sb = StringBuilder()
        for (m in items.reversed()) {
            if (sb.length + m.text.length > PROMPT_CHARS) break
            sb.append("- ").append(m.text).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

// ------------------------------------------------------------------ calendar

object DeviceCalendar {
    fun canRead(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Upcoming events for the next [days] days as prompt lines, or null without permission. */
    fun upcoming(ctx: Context, days: Int = 7, max: Int = 30): String? {
        if (!canRead(ctx)) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 2 * 3600_000L)
            android.content.ContentUris.appendId(it, now + days * 86_400_000L)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val fmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.US)
        val dayFmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.US)
        val zone = ZoneId.systemDefault()
        val lines = mutableListOf<String>()
        return try {
            ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && lines.size < max) {
                    val title = c.getString(0) ?: "(no title)"
                    val begin = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                    val end = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(zone)
                    val allDay = c.getInt(3) == 1
                    val loc = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
                    lines += if (allDay) "${dayFmt.format(java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.of("UTC")))} (all day): $title$loc"
                    else "${fmt.format(begin)}-${end.toLocalTime()}: $title$loc"
                }
            }
            if (lines.isEmpty()) "(no events)" else lines.joinToString("\n")
        } catch (e: Exception) {
            null
        }
    }

    data class Instance(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

    /** Event instances overlapping [from, to) (epoch ms), sorted by start. Empty without permission. */
    fun instances(ctx: Context, from: Long, to: Long, max: Int = 50): List<Instance> {
        if (!canRead(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from)
            android.content.ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val out = mutableListOf<Instance>()
        runCatching {
            ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    out += Instance(c.getString(0) ?: "(no title)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4) ?: "")
                }
            }
        }
        return out
    }

    /** Adds an event to the primary writable calendar. Returns a human-readable result; throws on failure. */
    fun add(ctx: Context, title: String, start: String, end: String, location: String): String {
        if (!canWrite(ctx)) throw IllegalStateException("no calendar permission")
        val s = parseLocal(start) ?: throw IllegalArgumentException("bad start time '$start'")
        val e = parseLocal(end)?.takeIf { it.isAfter(s) } ?: s.plusHours(1)
        val calId = pickCalendar(ctx) ?: throw IllegalStateException("no writable calendar on this phone")
        val zone = ZoneId.systemDefault()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title.ifBlank { "Event" })
            put(CalendarContract.Events.DTSTART, s.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, e.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("insert failed")
        val f = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())
        return "${title.ifBlank { "Event" }}, ${f.format(s)}-${e.toLocalTime()}" + if (location.isNotBlank()) ", $location" else ""
    }

    fun parseLocal(s: String): LocalDateTime? {
        val t = s.trim().removeSuffix("Z").let { if (it.length > 19 && (it[19] == '+' || it[19] == '-')) it.substring(0, 19) else it }
        if (t.isEmpty()) return null
        return runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t.take(10)).atTime(9, 0) }.getOrNull()
    }

    private fun pickCalendar(ctx: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE)
        var best: Long? = null
        var bestScore = -1
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(2)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getInt(3) == 1) 1 else 0)
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }
}

// ------------------------------------------------------------------ navigation

object Navigator {
    private const val CH_NAV = "jarvis_nav"

    fun navigate(ctx: Context, destination: String) {
        val intent = bestIntent(ctx, destination)
        if (Store.appVisible) {
            try { ctx.startActivity(intent); return } catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        // App not in front: background activity starts are blocked, so offer a tappable notification.
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel(CH_NAV, "Navigation", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_NAV).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Navigate to $destination").setContentText("Tap to start navigation")
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { nm.notify(7, n) }
    }

    private fun bestIntent(ctx: Context, dest: String): Intent {
        val q = Uri.encode(dest)
        val pm = ctx.packageManager
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q"))
        val pick = listOf(nav, geo).firstOrNull { it.resolveActivity(pm) != null } ?: web
        return pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
