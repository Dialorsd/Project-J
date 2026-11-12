package com.dialorsd.jarvis

import android.content.Context

/**
 * All model IDs and endpoints live here. Every model ID is also editable in Settings,
 * so a renamed model does not need a new build.
 */
object Defaults {
    // Gemini API (ai.google.dev). Text model and a second free-tier model used if the first is
    // over quota, overloaded or unknown.
    const val GEMINI_MODEL = "gemini-3.6-flash"
    const val GEMINI_FALLBACK_MODEL = "gemini-3.1-flash-lite"
    const val GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview"
    const val GEMINI_TTS_FALLBACK_MODEL = "gemini-2.5-flash-preview-tts"
    const val GEMINI_TTS_VOICE = "Charon"
    const val TTS_STYLE = "calm, measured and dry, like an unflappable British butler"
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val GEMINI_INTERACTIONS = "https://generativelanguage.googleapis.com/v1beta/interactions"

    // Google Search grounding is free (500 requests/day) only on Gemini 2.5 Flash / Flash-Lite,
    // so web lookups use a separate call with this model.
    const val SEARCH_MODEL = "gemini-2.5-flash"
    const val SEARCH_FALLBACK_MODEL = "gemini-2.5-flash-lite"

    const val NTFY_SERVER = "https://ntfy.sh"

    // Groq OpenAI-compatible chat completions (optional backup brain).
    const val GROQ_MODEL = "llama-3.3-70b-versatile"
    const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    const val HISTORY_TURNS = 12

    // Gemini Live API (realtime voice). Both models are listed as free of charge on the free tier
    // (ai.google.dev/gemini-api/docs/pricing, checked 2026-11-12). 3.8 Live is the current default;
    // 3.1 Flash Live Preview is the documented "legacy preview" and is tried if the first is refused.
    const val LIVE_MODEL = "gemini-3.8-live"
    const val LIVE_FALLBACK_MODEL = "gemini-3.1-flash-live-preview"
    const val LIVE_WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    // Open-Meteo (free, no key, non-commercial use, CC BY 4.0 data).
    const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
}

enum class SpeechLang(val label: String) { AUTO("AUTO"), EN("EN"), UK("UK") }

enum class VoiceMode(val label: String) { GEMINI("Gemini voice"), PHONE("Phone voice"), MUTED("Muted") }

/** Settings stored only in app-private SharedPreferences (never logged, never backed up). */
class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = p.getString("gemini_key", "") ?: ""
        set(v) = p.edit().putString("gemini_key", v.trim()).apply()
    var groqKey: String
        get() = p.getString("groq_key", "") ?: ""
        set(v) = p.edit().putString("groq_key", v.trim()).apply()
    var model: String
        get() = p.getString("model", null)?.takeIf { it.isNotBlank() } ?: Defaults.GEMINI_MODEL
        set(v) = p.edit().putString("model", v.trim()).apply()
    var fallbackModel: String
        get() = p.getString("fallback_model", null) ?: Defaults.GEMINI_FALLBACK_MODEL
        set(v) = p.edit().putString("fallback_model", v.trim()).apply()
    var ttsModel: String
        get() = p.getString("tts_model", null)?.takeIf { it.isNotBlank() } ?: Defaults.GEMINI_TTS_MODEL
        set(v) = p.edit().putString("tts_model", v.trim()).apply()
    var ttsVoice: String
        get() = p.getString("tts_voice", null)?.takeIf { it.isNotBlank() } ?: Defaults.GEMINI_TTS_VOICE
        set(v) = p.edit().putString("tts_voice", v.trim()).apply()
    var groqModel: String
        get() = p.getString("groq_model", null)?.takeIf { it.isNotBlank() } ?: Defaults.GROQ_MODEL
        set(v) = p.edit().putString("groq_model", v.trim()).apply()
    var speechLang: SpeechLang
        get() = runCatching { SpeechLang.valueOf(p.getString("speech_lang", "AUTO")!!) }.getOrDefault(SpeechLang.AUTO)
        set(v) = p.edit().putString("speech_lang", v.name).apply()
    var voiceMode: VoiceMode
        get() = runCatching { VoiceMode.valueOf(p.getString("voice_mode", "GEMINI")!!) }.getOrDefault(VoiceMode.GEMINI)
        set(v) = p.edit().putString("voice_mode", v.name).apply()

    /** 0 = strictly professional, 1 = openly sarcastic. */
    var humour: Float
        get() = p.getFloat("humour", 0.4f)
        set(v) = p.edit().putFloat("humour", v.coerceIn(0f, 1f)).apply()

    var ttsStyle: String
        get() = p.getString("tts_style", null) ?: Defaults.TTS_STYLE
        set(v) = p.edit().putString("tts_style", v.trim()).apply()
    var webSearch: Boolean
        get() = p.getBoolean("web_search", true)
        set(v) = p.edit().putBoolean("web_search", v).apply()
    var searchModel: String
        get() = p.getString("search_model", null)?.takeIf { it.isNotBlank() } ?: Defaults.SEARCH_MODEL
        set(v) = p.edit().putString("search_model", v.trim()).apply()

    // PC link (ntfy)
    var pcLink: Boolean
        get() = p.getBoolean("pc_link", false)
        set(v) = p.edit().putBoolean("pc_link", v).apply()
    var ntfyServer: String
        get() = p.getString("ntfy_server", null)?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: Defaults.NTFY_SERVER
        set(v) = p.edit().putString("ntfy_server", v.trim()).apply()
    var ntfyTopic: String
        get() = p.getString("ntfy_topic", null)?.takeIf { it.isNotBlank() } ?: newTopic().also { ntfyTopic = it }
        set(v) = p.edit().putString("ntfy_topic", v.trim()).apply()
    /** Unix seconds up to which PC messages have been received. */
    var ntfySince: Long
        get() = p.getLong("ntfy_since", 0L)
        set(v) = p.edit().putLong("ntfy_since", v).apply()
    var ntfySeen: String
        get() = p.getString("ntfy_seen", "") ?: ""
        set(v) = p.edit().putString("ntfy_seen", v).apply()

    // ---- v3: Live mode ----
    var liveModel: String
        get() = p.getString("live_model", null)?.takeIf { it.isNotBlank() } ?: Defaults.LIVE_MODEL
        set(v) = p.edit().putString("live_model", v.trim()).apply()
    var liveFallbackModel: String
        get() = p.getString("live_fallback_model", null) ?: Defaults.LIVE_FALLBACK_MODEL
        set(v) = p.edit().putString("live_fallback_model", v.trim()).apply()
    /** Let him be interrupted while speaking (needs good echo cancellation on the loudspeaker). */
    var liveBargeIn: Boolean
        get() = p.getBoolean("live_barge_in", true)
        set(v) = p.edit().putBoolean("live_barge_in", v).apply()
    /** Google Search built into the Live model (otherwise the separate 2.5 Flash search helper is used). */
    var liveGrounding: Boolean
        get() = p.getBoolean("live_grounding", false)
        set(v) = p.edit().putBoolean("live_grounding", v).apply()

    // ---- v3: morning call ----
    var morningOn: Boolean
        get() = p.getBoolean("morning_on", false)
        set(v) = p.edit().putBoolean("morning_on", v).apply()
    /** Minutes after midnight. */
    var morningTime: Int
        get() = p.getInt("morning_time", 7 * 60 + 30)
        set(v) = p.edit().putInt("morning_time", v.coerceIn(0, 24 * 60 - 1)).apply()
    /** Bit 0 = Monday ... bit 6 = Sunday. Default Monday-Friday. */
    var morningDays: Int
        get() = p.getInt("morning_days", 0b0011111)
        set(v) = p.edit().putInt("morning_days", v and 0x7F).apply()
    var city: String
        get() = p.getString("city", "") ?: ""
        set(v) = p.edit().putString("city", v.trim()).apply()
    /** "lat,lon,Resolved name" from the geocoder, or empty. */
    var cityGeo: String
        get() = p.getString("city_geo", "") ?: ""
        set(v) = p.edit().putString("city_geo", v).apply()
    var lastMorningAt: Long
        get() = p.getLong("last_morning_at", 0L)
        set(v) = p.edit().putLong("last_morning_at", v).apply()

    // ---- v3: nagging reminders ----
    var nagOn: Boolean
        get() = p.getBoolean("nag_on", true)
        set(v) = p.edit().putBoolean("nag_on", v).apply()
    var nagMinutes: Int
        get() = p.getInt("nag_minutes", 10)
        set(v) = p.edit().putInt("nag_minutes", v.coerceIn(1, 120)).apply()
    var nagTimes: Int
        get() = p.getInt("nag_times", 3)
        set(v) = p.edit().putInt("nag_times", v.coerceIn(1, 10)).apply()

    // ---- v3: proactive remarks ----
    var eventAlerts: Boolean
        get() = p.getBoolean("event_alerts", true)
        set(v) = p.edit().putBoolean("event_alerts", v).apply()
    var eventMinutes: Int
        get() = p.getInt("event_minutes", 15)
        set(v) = p.edit().putInt("event_minutes", v.coerceIn(1, 180)).apply()
    var eventSpeak: Boolean
        get() = p.getBoolean("event_speak", false)
        set(v) = p.edit().putBoolean("event_speak", v).apply()
    var eventNotified: String
        get() = p.getString("event_notified", "") ?: ""
        set(v) = p.edit().putString("event_notified", v).apply()
    var batteryAlerts: Boolean
        get() = p.getBoolean("battery_alerts", false)
        set(v) = p.edit().putBoolean("battery_alerts", v).apply()
    var batteryLevel: Int
        get() = p.getInt("battery_level", 15)
        set(v) = p.edit().putInt("battery_level", v.coerceIn(5, 50)).apply()
    var batteryWarned: Boolean
        get() = p.getBoolean("battery_warned", false)
        set(v) = p.edit().putBoolean("battery_warned", v).apply()

    var askedNotifications: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) = p.edit().putBoolean("asked_notif", v).apply()

    // ---- v4: reading notifications (nothing is shared until the user ticks apps) ----
    /** Comma-separated package names whose notification text Jarvis may keep and read on request. */
    var notifAllowed: Set<String>
        get() = (p.getString("notif_allowed", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        set(v) = p.edit().putString("notif_allowed", v.joinToString(",")).apply()
    /** Say new notifications from allowed apps aloud while Live mode runs (off by default). */
    var notifAnnounce: Boolean
        get() = p.getBoolean("notif_announce", false)
        set(v) = p.edit().putBoolean("notif_announce", v).apply()

    // ---- v4: watchers ----
    var watchWifiOnly: Boolean
        get() = p.getBoolean("watch_wifi_only", false)
        set(v) = p.edit().putBoolean("watch_wifi_only", v).apply()

    // ---- v4: PC remote (phone -> PC commands, HMAC-signed) ----
    /** Shared HMAC secret (base64url, 32 random bytes). Empty = not paired. */
    var pcSecret: String
        get() = p.getString("pc_secret", "") ?: ""
        set(v) = p.edit().putString("pc_secret", v).apply()
    /** Separate ntfy topic the PC listens on for commands. */
    var pcCmdTopic: String
        get() = p.getString("pc_cmd_topic", "") ?: ""
        set(v) = p.edit().putString("pc_cmd_topic", v).apply()
    /** Nonces of PC replies already handled (replay protection), comma-separated. */
    var pcReplySeen: String
        get() = p.getString("pc_reply_seen", "") ?: ""
        set(v) = p.edit().putString("pc_reply_seen", v).apply()

    // ---- v4: interpreter ----
    var interpA: String
        get() = p.getString("interp_a", "uk") ?: "uk"
        set(v) = p.edit().putString("interp_a", v).apply()
    var interpB: String
        get() = p.getString("interp_b", "en") ?: "en"
        set(v) = p.edit().putString("interp_b", v).apply()

    // ---- v4: daily wrap-up ----
    var wrapOn: Boolean
        get() = p.getBoolean("wrap_on", false)
        set(v) = p.edit().putBoolean("wrap_on", v).apply()
    /** Minutes after midnight. */
    var wrapTime: Int
        get() = p.getInt("wrap_time", 21 * 60)
        set(v) = p.edit().putInt("wrap_time", v.coerceIn(0, 24 * 60 - 1)).apply()
    /** Ring (call) instead of only posting a notification. */
    var wrapCall: Boolean
        get() = p.getBoolean("wrap_call", false)
        set(v) = p.edit().putBoolean("wrap_call", v).apply()
}

fun newTopic(): String {
    val chars = "abcdefghijkmnpqrstuvwxyz23456789"
    val rnd = java.security.SecureRandom()
    return "jarvis-" + (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }
