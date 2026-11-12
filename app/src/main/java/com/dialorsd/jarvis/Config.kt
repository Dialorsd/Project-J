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
    const val GEMINI_TTS_VOICE = "Charon"
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

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

    var askedNotifications: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) = p.edit().putBoolean("asked_notif", v).apply()
}

fun hasCyrillic(s: String): Boolean = s.any { it in 'Ѐ'..'ӿ' }
