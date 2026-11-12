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

    var askedNotifications: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) = p.edit().putBoolean("asked_notif", v).apply()
}

fun newTopic(): String {
    val chars = "abcdefghijkmnpqrstuvwxyz23456789"
    val rnd = java.security.SecureRandom()
    return "jarvis-" + (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }
