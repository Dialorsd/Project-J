package com.dialorsd.jarvis

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Morning call: an exact alarm at the chosen time and weekdays rings the usual call screen; on Answer
 * Jarvis reads a short briefing (date, weather, calendar, timers, overnight PC pings, open notes).
 * Declined or missed: the briefing is posted as a notification and in the chat.
 */
object Morning {
    private const val REQ = 900_001
    private const val CH_BRIEF = "jarvis_briefing"
    const val ACTION_MORNING = "com.dialorsd.jarvis.MORNING"

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, MorningReceiver::class.java).setAction(ACTION_MORNING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Next ring time (epoch ms) for the current settings, or null if off / no day selected. */
    fun nextTime(s: Settings, now: LocalDateTime = LocalDateTime.now()): Long? {
        if (!s.morningOn || s.morningDays == 0) return null
        val t = LocalTime.of(s.morningTime / 60, s.morningTime % 60)
        for (d in 0..7) {
            val day = now.toLocalDate().plusDays(d.toLong())
            val bit = 1 shl (day.dayOfWeek.value - 1) // Monday = bit 0
            if (s.morningDays and bit == 0) continue
            val at = day.atTime(t)
            if (at.isAfter(now.plusSeconds(30))) return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    /** (Re)arms or cancels the morning alarm. Cheap; safe to call often. */
    fun schedule(ctx: Context) {
        val s = Settings(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTime(s)
        if (at == null) { am.cancel(pi(ctx)); return }
        Timers.wakeAt(ctx, at, pi(ctx), alarmClock = true)
    }

    fun ringNow(ctx: Context) {
        val call = CallInfo(Store.nextId(), "morning", "Morning briefing", "")
        Store.putCall(call)
        Caller.ring(ctx, call)
    }

    // ---------- briefing ----------

    /** Plain facts for the brain (and the fallback text if the brain is unavailable). Blocking. */
    fun facts(ctx: Context, uk: Boolean): String {
        val s = Settings(ctx)
        val sb = StringBuilder()
        sb.append("Date: ").append(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())).append('\n')
        val w = runCatching { Weather.today(s) }
        sb.append("Weather: ").append(w.getOrNull() ?: if (s.city.isBlank()) "no city set in Settings" else "unavailable (${w.exceptionOrNull()?.message})").append('\n')
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + 86_400_000L
        val ev = if (!DeviceCalendar.canRead(ctx)) "calendar not allowed" else
            DeviceCalendar.instances(ctx, start, end).joinToString("; ") {
                (if (it.allDay) "all day" else SimpleDateFormat("HH:mm", Locale.US).format(Date(it.begin))) + " " + it.title +
                    (if (it.location.isNotBlank()) " @ ${it.location}" else "")
            }.ifBlank { "none" }
        sb.append("Today's events: ").append(ev).append('\n')
        val timers = Store.loadTimers().filter { it.kind == "timer" }.sortedBy { it.endAt }
        sb.append("Timers and reminders: ").append(if (timers.isEmpty()) "none" else timers.joinToString("; ") {
            "${it.label} at ${SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(it.endAt))}"
        }).append('\n')
        val since = if (s.lastMorningAt > 0) maxOf(s.lastMorningAt, System.currentTimeMillis() - 24 * 3600_000L)
            else System.currentTimeMillis() - 12 * 3600_000L
        val pings = Store.pcPingsSince(since)
        sb.append("Messages from his computer overnight: ").append(if (pings.isEmpty()) "none" else pings.takeLast(5).joinToString("; ")).append('\n')
        sb.append(Notes.forPrompt(800).ifBlank { "Notes and lists: none." })
        return sb.toString()
    }

    /** The spoken briefing: brain first, plain facts as the fallback. Blocking (network). */
    fun briefingText(ctx: Context, uk: Boolean): String {
        val s = Settings(ctx)
        val f = facts(ctx, uk)
        s.lastMorningAt = System.currentTimeMillis()
        val said = runCatching { Brain.briefing(s, f, uk) }.getOrNull()
        return said?.takeIf { it.isNotBlank() } ?: ((if (uk) "Доброго ранку, сер. " else "Good morning, sir. ") + f.replace('\n', ' '))
    }

    /** Declined/missed: post the briefing as a notification and in the chat. Blocking (network). */
    fun deliverAsNotification(ctx: Context) {
        val uk = Store.lastLanguageIsUkrainian()
        val text = briefingText(ctx, uk)
        Store.addMessage("jarvis", text)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_BRIEF, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_BRIEF).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (uk) "Ранковий огляд" else "Morning briefing").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(REQ, n) }
    }
}

/** Fires at the morning time: ring, then arm the next one. */
class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Morning.ACTION_MORNING) return
        val s = Settings(context)
        if (s.morningOn) Morning.ringNow(context)
        Morning.schedule(context)
    }
}

/** Open-Meteo: free, no key, non-commercial use; data CC BY 4.0 (open-meteo.com). */
object Weather {
    private val CODES = mapOf(
        0 to "clear sky", 1 to "mainly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "rime fog",
        51 to "light drizzle", 53 to "drizzle", 55 to "dense drizzle", 56 to "freezing drizzle", 57 to "freezing drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "heavy freezing rain",
        71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains", 80 to "light showers",
        81 to "showers", 82 to "violent showers", 85 to "snow showers", 86 to "heavy snow showers",
        95 to "thunderstorm", 96 to "thunderstorm with hail", 97 to "heavy thunderstorm", 99 to "thunderstorm with hail",
    )

    /** Returns "lat,lon,Name" for a city name typed in Settings. Throws on failure. */
    fun geocode(city: String): String {
        val name = city.substringBefore(',').trim()
        if (name.length < 2) throw IOException("city name too short")
        val lang = if (hasCyrillic(name)) "uk" else "en"
        val url = Defaults.GEOCODE_URL + "?name=" + URLEncoder.encode(name, "UTF-8") + "&count=1&language=$lang&format=json"
        val o = JSONObject(get(url))
        val r = o.optJSONArray("results")?.optJSONObject(0) ?: throw IOException("city \"$name\" not found")
        val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        return "${r.getDouble("latitude")},${r.getDouble("longitude")},$label"
    }

    /** One line about today's weather for the saved city, or null if no city is set. Throws on network errors. */
    fun today(s: Settings): String? {
        if (s.city.isBlank()) return null
        var geo = s.cityGeo
        if (geo.isBlank()) { geo = geocode(s.city); s.cityGeo = geo }
        val parts = geo.split(',')
        val lat = parts[0]; val lon = parts[1]
        val url = Defaults.FORECAST_URL + "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=1"
        val o = JSONObject(get(url))
        val cur = o.optJSONObject("current")
        val d = o.optJSONObject("daily")
        val place = parts.drop(2).joinToString(",").ifBlank { s.city }
        val now = cur?.let { "now ${Math.round(it.optDouble("temperature_2m"))}°C, ${CODES[it.optInt("weather_code", -1)] ?: "?"}" } ?: ""
        val day = d?.let {
            val max = it.optJSONArray("temperature_2m_max")?.optDouble(0)
            val min = it.optJSONArray("temperature_2m_min")?.optDouble(0)
            val rain = it.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
            val code = it.optJSONArray("weather_code")?.optInt(0, -1) ?: -1
            "today ${CODES[code] ?: "?"}, ${min?.let { v -> Math.round(v) }}..${max?.let { v -> Math.round(v) }}°C" +
                (if (rain >= 0) ", chance of rain $rain%" else "")
        } ?: ""
        return "$place: " + listOf(now, day).filter { it.isNotBlank() }.joinToString("; ")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IOException("weather service HTTP $code" + (reason?.let { ": $it" } ?: ""))
            }
            return body
        } finally {
            c.disconnect()
        }
    }
}
