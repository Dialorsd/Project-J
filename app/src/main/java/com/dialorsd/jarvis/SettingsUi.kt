package com.dialorsd.jarvis

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gemini prebuilt voices with their documented style tags (ai.google.dev speech-generation page). */
private val MALE_VOICES = listOf(
    "Charon" to "Informative", "Algieba" to "Smooth", "Sadaltager" to "Knowledgeable", "Rasalgethi" to "Informative",
    "Iapetus" to "Clear", "Schedar" to "Even", "Algenib" to "Gravelly", "Orus" to "Firm", "Alnilam" to "Firm",
    "Puck" to "Upbeat", "Fenrir" to "Excitable", "Enceladus" to "Breathy", "Umbriel" to "Easy-going",
    "Achird" to "Friendly", "Zubenelgenubi" to "Casual", "Sadachbia" to "Lively",
)
private val OTHER_VOICES = listOf(
    "Zephyr" to "Bright", "Kore" to "Firm", "Leda" to "Youthful", "Aoede" to "Breezy", "Callirrhoe" to "Easy-going",
    "Autonoe" to "Bright", "Despina" to "Smooth", "Erinome" to "Clear", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
    "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Vindemiatrix" to "Gentle", "Sulafat" to "Warm",
)

@Composable
internal fun SectionHeader(title: String, open: Boolean, toggle: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Cyan.copy(alpha = 0.25f))
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
        Text(if (open) "▲" else "▼", color = Dim)
    }
}

@Composable
internal fun Hint(text: String) = Text(text, color = Dim, fontSize = 12.sp)

@Composable
internal fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFDCEBF0))
            if (sub.isNotBlank()) Text(sub, color = Dim, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
internal fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() }.take(3)) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
fun SettingsScreen(settings: Settings, resumeTick: Int, close: () -> Unit) {
    var showMemory by remember { mutableStateOf(false) }
    if (showMemory) MemoryScreen { showMemory = false }
    else SettingsMain(settings, resumeTick, close) { showMemory = true }
}

private val DAY_NAMES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

@Composable
private fun SettingsMain(settings: Settings, resumeTick: Int, close: () -> Unit, openMemory: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var openSections by remember { mutableStateOf(if (settings.geminiKey.isBlank()) setOf("KEYS") else emptySet()) }
    fun isOpen(t: String) = t in openSections
    fun toggle(t: String) { openSections = if (t in openSections) openSections - t else openSections + t }

    var gemini by remember { mutableStateOf(settings.geminiKey) }
    var groq by remember { mutableStateOf(settings.groqKey) }
    var model by remember { mutableStateOf(settings.model) }
    var fallback by remember { mutableStateOf(settings.fallbackModel) }
    var tts by remember { mutableStateOf(settings.ttsModel) }
    var voice by remember { mutableStateOf(settings.ttsVoice) }
    var style by remember { mutableStateOf(settings.ttsStyle) }
    var groqModel by remember { mutableStateOf(settings.groqModel) }
    var searchModel by remember { mutableStateOf(settings.searchModel) }
    var webSearch by remember { mutableStateOf(settings.webSearch) }
    var groqSearch by remember { mutableStateOf(settings.groqSearchModel) }
    var groqSearchFb by remember { mutableStateOf(settings.groqSearchFallback) }
    var searchTick by remember { mutableStateOf(0) }
    var lang by remember { mutableStateOf(settings.speechLang) }
    var mode by remember { mutableStateOf(settings.voiceMode) }
    var humour by remember { mutableStateOf(settings.humour) }
    var showVoices by remember { mutableStateOf(false) }
    var previewMsg by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var pcLink by remember { mutableStateOf(settings.pcLink) }
    var topic by remember { mutableStateOf(settings.ntfyTopic) }
    var server by remember { mutableStateOf(settings.ntfyServer) }
    // v3
    var liveModels by remember { mutableStateOf(settings.liveModels.joinToString("\n")) }
    var quotaSaver by remember { mutableStateOf(settings.liveQuotaSaver) }
    var gateSens by remember { mutableStateOf(settings.liveGateSensitivity) }
    var idleMin by remember { mutableStateOf(settings.liveIdleMinutes.toString()) }
    var mainMode by remember { mutableStateOf(settings.mainMode) }
    var spentAction by remember { mutableStateOf(settings.spentAction) }
    var hearing by remember { mutableStateOf(settings.hearing) }
    var groqTtsOn by remember { mutableStateOf(settings.groqTtsOn) }
    var groqTtsVoice by remember { mutableStateOf(settings.groqTtsVoice) }
    var groqVoiceModel by remember { mutableStateOf(settings.groqVoiceModel) }
    var groqSttModel by remember { mutableStateOf(settings.groqSttModel) }
    var groqTtsModel by remember { mutableStateOf(settings.groqTtsModel) }
    var hfBargeIn by remember { mutableStateOf(settings.hfBargeIn) }
    var afterCall by remember { mutableStateOf(settings.afterCall) }
    var postIdle by remember { mutableStateOf(settings.postCallIdleSec.toString()) }
    var groqPreviewMsg by remember { mutableStateOf("") }
    var groqPreviewing by remember { mutableStateOf(false) }
    var groqTick by remember { mutableStateOf(0) }
    var preferGroq by remember { mutableStateOf(settings.handsFreePreferGroq) }
    var quietBeep by remember { mutableStateOf(settings.handsFreeQuietBeep) }
    var bargeIn by remember { mutableStateOf(settings.liveBargeIn) }
    var grounding by remember { mutableStateOf(settings.liveGrounding) }
    var morningOn by remember { mutableStateOf(settings.morningOn) }
    var morningTime by remember { mutableStateOf("%02d:%02d".format(settings.morningTime / 60, settings.morningTime % 60)) }
    var morningDays by remember { mutableStateOf(settings.morningDays) }
    var city by remember { mutableStateOf(settings.city) }
    var cityMsg by remember { mutableStateOf(settings.cityGeo.split(',').drop(2).joinToString(",").let { if (it.isBlank()) "" else "Found: $it" }) }
    var nagOn by remember { mutableStateOf(settings.nagOn) }
    var nagMinutes by remember { mutableStateOf(settings.nagMinutes.toString()) }
    var nagTimes by remember { mutableStateOf(settings.nagTimes.toString()) }
    var eventAlerts by remember { mutableStateOf(settings.eventAlerts) }
    var eventMinutes by remember { mutableStateOf(settings.eventMinutes.toString()) }
    var eventSpeak by remember { mutableStateOf(settings.eventSpeak) }
    var batteryAlerts by remember { mutableStateOf(settings.batteryAlerts) }
    var batteryLevel by remember { mutableStateOf(settings.batteryLevel.toString()) }
    var saveMsg by remember { mutableStateOf("") }

    var calTick by remember { mutableStateOf(0) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { calTick++ }
    val pcStatus by PcLinkService.status

    fun parseTime(t: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    fun save() {
        settings.geminiKey = gemini; settings.groqKey = groq
        settings.model = model; settings.fallbackModel = fallback; settings.ttsModel = tts
        settings.ttsVoice = voice; settings.ttsStyle = style; settings.groqModel = groqModel
        // Editing a model field gives that model a fresh chance.
        if (searchModel.trim() != settings.searchModel) settings.unblockSearchModel(searchModel.trim())
        settings.searchModel = searchModel; settings.webSearch = webSearch
        settings.groqSearchModel = groqSearch; settings.groqSearchFallback = groqSearchFb
        searchTick++
        settings.speechLang = lang; settings.voiceMode = mode; settings.humour = humour
        settings.ntfyServer = server
        if (topic.isNotBlank()) settings.ntfyTopic = topic
        val newList = liveModels.lines().map { it.trim().removePrefix("models/") }.filter { it.isNotBlank() }
        // A model added or renamed gets a fresh chance.
        newList.filter { it !in settings.liveModels }.forEach { settings.unskipLiveModel(it) }
        if (newList.isNotEmpty()) settings.liveModels = newList
        settings.liveQuotaSaver = quotaSaver; settings.liveGateSensitivity = gateSens
        idleMin.toIntOrNull()?.let { settings.liveIdleMinutes = it }
        settings.mainMode = mainMode; settings.spentAction = spentAction; settings.hearing = hearing
        settings.groqTtsOn = groqTtsOn; settings.groqTtsVoice = groqTtsVoice; settings.groqVoiceModel = groqVoiceModel
        settings.groqSttModel = groqSttModel; settings.groqTtsModel = groqTtsModel; settings.hfBargeIn = hfBargeIn
        settings.afterCall = afterCall
        postIdle.toIntOrNull()?.let { settings.postCallIdleSec = it }
        settings.handsFreePreferGroq = preferGroq
        settings.handsFreeQuietBeep = quietBeep
        settings.liveBargeIn = bargeIn; settings.liveGrounding = grounding
        settings.morningOn = morningOn; settings.morningDays = morningDays
        parseTime(morningTime)?.let { settings.morningTime = it }
        if (city.trim() != settings.city) { settings.city = city; settings.cityGeo = "" }
        settings.nagOn = nagOn
        nagMinutes.toIntOrNull()?.let { settings.nagMinutes = it }
        nagTimes.toIntOrNull()?.let { settings.nagTimes = it }
        settings.eventAlerts = eventAlerts; settings.eventSpeak = eventSpeak
        eventMinutes.toIntOrNull()?.let { settings.eventMinutes = it }
        settings.batteryAlerts = batteryAlerts
        batteryLevel.toIntOrNull()?.let { settings.batteryLevel = it }
        // Re-arm alarms and receivers to match (cheap, no network).
        val app = ctx.applicationContext
        Proactive.registerBatteryReceiver(app)
        Thread {
            runCatching { Morning.schedule(app) }
            runCatching { Proactive.scheduleEvents(app) }
            runCatching { Proactive.scheduleBatteryCheck(app) }
        }.start()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SETTINGS", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 3.sp,
                modifier = Modifier.weight(1f))
            if (settings.geminiKey.isNotBlank()) OutlinedButton(onClick = close) { Text("BACK") }
        }
        Hint("Tap a section to open it. Changes apply when you tap SAVE at the bottom.")

        if (settings.geminiKey.isBlank()) {
            Text(
                "Jarvis needs a free Gemini API key to think.\n" +
                    "1. Open aistudio.google.com and sign in with a Google account.\n" +
                    "2. Tap \"Get API key\" and create a key.\n" +
                    "3. Copy it and paste it below. It is stored only on this phone.",
                color = Color(0xFFDCEBF0), fontSize = 14.sp
            )
            OutlinedButton(onClick = { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) {
                Text("OPEN AI STUDIO")
            }
        }

        // ---------------------------------------------------------------- KEYS
        SectionHeader("KEYS", isOpen("KEYS")) { toggle("KEYS") }
        if (isOpen("KEYS")) {
            OutlinedTextField(gemini, { gemini = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key (required)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(groq, { groq = it }, Modifier.fillMaxWidth(), label = { Text("Groq API key (free: Groq voice mode, backup brain, web search)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
        }

        // ---------------------------------------------------------------- VOICE
        SectionHeader("VOICE", isOpen("VOICE")) { toggle("VOICE") }
        if (isOpen("VOICE")) {
            Text("Speech language (push-to-talk)", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechLang.entries.forEach { l -> FilterChip(lang == l, { lang = l }, label = { Text(l.label) }) }
            }
            Text("Voice", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, label = { Text(m.label) }) }
            }
            val tag = (MALE_VOICES + OTHER_VOICES).firstOrNull { it.first.equals(voice, true) }?.second ?: "custom"
            OutlinedButton(onClick = { showVoices = !showVoices }, modifier = Modifier.fillMaxWidth()) {
                Text("Gemini voice: $voice ($tag)  " + if (showVoices) "▲" else "▼")
            }
            if (showVoices) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
                    Text("Male", color = Cyan, fontSize = 12.sp)
                    MALE_VOICES.forEach { (n, t) -> VoiceRow(n, t, n == voice) { voice = n; previewMsg = "" } }
                    Spacer(Modifier.height(6.dp))
                    Text("Other", color = Cyan, fontSize = 12.sp)
                    OTHER_VOICES.forEach { (n, t) -> VoiceRow(n, t, n == voice) { voice = n; previewMsg = "" } }
                }
            }
            OutlinedTextField(style, { style = it }, Modifier.fillMaxWidth(), label = { Text("Delivery style (Gemini voice)") }, maxLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !previewing && gemini.isNotBlank(), onClick = {
                    save()
                    previewing = true
                    previewMsg = "Generating sample…"
                    scope.launch {
                        val err = Voice.preview(ctx, voice, style)
                        previewMsg = if (err == null) "Gemini voice works." else "Gemini voice FAILED: $err\nThe phone voice will be used instead."
                        previewing = false
                    }
                }) { Text("PREVIEW") }
                if (previewing) TextButton(onClick = { Voice.stop() }) { Text("STOP") }
            }
            if (previewMsg.isNotBlank()) Text(previewMsg, color = if (previewMsg.startsWith("Gemini voice FAILED")) Color(0xFFFFC48A) else Dim, fontSize = 12.sp)
            Hint("Push-to-talk: English replies use the Gemini voice; Ukrainian replies use the phone's own voice. " +
                "Live mode uses the same Gemini voice for both languages.")
            Text("Humour: " + when {
                humour < 0.2f -> "professional"; humour < 0.45f -> "polite, a little dry"
                humour < 0.75f -> "dry wit"; else -> "sarcastic"
            }, color = Dim)
            Slider(humour, { humour = it })
        }

        // ---------------------------------------------------------------- LIVE
        SectionHeader("LIVE MODE", isOpen("LIVE")) { toggle("LIVE") }
        if (isOpen("LIVE")) {
            Hint("A hands-free conversation: tap LIVE CONVERSATION on the main screen and just talk. It uses the microphone " +
                "and internet only while it runs (a notification with End is shown) and ends itself after the silent minutes below.")
            Text("The conversation button starts", color = Dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MainMode.entries.forEach { m -> FilterChip(mainMode == m, { mainMode = m }, label = { Text(m.label) }) }
            }
            Hint("Gemini Live: natural realtime voice, uses Google's free Live quota. Groq voice mode (hands-free): Groq Whisper hears you, " +
                "a fast Groq model thinks, Groq's English voice answers (Gemini and the phone voice are the backups); uses no Live quota, " +
                "needs the Groq key. Phone hands-free: the phone's own speech recognition instead of Whisper.")
            Text("When Gemini Live quota is spent", color = Dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpentAction.entries.forEach { a -> FilterChip(spentAction == a, { spentAction = a }, label = { Text(a.label) }) }
            }
            Hint("Chain: Gemini Live models in the list below, one after another, then this choice. Groq voice mode without a Groq key runs as phone hands-free.")
            SwitchRow("Save Live quota (send audio only when I speak)", "Off = always stream the microphone, as before. " +
                "Turn off if Jarvis misses the start of what you say or does not hear you.", quotaSaver) { quotaSaver = it }
            if (quotaSaver) {
                Text("Voice detection sensitivity: " + when {
                    gateSens < 0.25f -> "low (loud rooms)"; gateSens < 0.6f -> "normal"; gateSens < 0.85f -> "high"; else -> "very high (quiet voice)"
                }, color = Dim)
                Slider(gateSens, { gateSens = it })
            }
            NumberField(idleMin, { idleMin = it }, "End after this many silent minutes", Modifier.fillMaxWidth())
            SwitchRow("Allow interrupting him", "Speak over Jarvis to stop him. Turn off if he keeps interrupting himself " +
                "on the loudspeaker (poor echo cancellation); then the mic pauses while he talks.", bargeIn) { bargeIn = it }
            SwitchRow("Google Search inside Live", "Off: web lookups go through the separate search helper (recommended: Gemini, Groq, Wikipedia in turn). " +
                "On: the Live model searches by itself. Google lists this as free for Live models, so it may be worth trying.", grounding) { grounding = it }
            OutlinedTextField(liveModels, { liveModels = it }, Modifier.fillMaxWidth(), label = { Text("Live models, one per line, tried in order") },
                minLines = 2, maxLines = 6)
            Hint("When a model runs out of free quota, Jarvis moves to the next one (also in the middle of a conversation) and skips it " +
                "until the daily reset at midnight Pacific time (${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(QuotaClock.nextPacificMidnight(System.currentTimeMillis())))} on this phone).")
            LiveStatusPanel(settings)
            Text("Phone hands-free", color = Dim)
            SwitchRow("Prefer Groq for phone hands-free", "Ask Groq first (free) and Gemini only if Groq fails, to save the Gemini quota. " +
                "Needs the Groq key.", preferGroq) { preferGroq = it }
            SwitchRow("Silence the listening beep", "Mutes the phone's beep each time the phone recognizer starts listening " +
                "(media and notification sound are muted for half a second).", quietBeep) { quietBeep = it }

            Spacer(Modifier.height(6.dp))
            Text("Groq voice mode (hands-free)", color = Cyan, fontSize = 13.sp)
            Text("Hearing", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Hearing.entries.forEach { h -> FilterChip(hearing == h, { hearing = h }, label = { Text(h.label) }) }
            }
            Hint("Groq Whisper understands English and Ukrainian and detects the language by itself. If Groq's limit is reached or it fails, " +
                "the phone recognizer takes over automatically (and Whisper comes back when Groq's limit is over).")
            SwitchRow("Speak with Groq's voice (English only)", "Ukrainian replies, long replies and any Groq failure use the Gemini voice, " +
                "then the phone voice. Groq's free voice allows about 100 requests and a few thousand characters a day.", groqTtsOn) { groqTtsOn = it }
            Text("Groq voice (male)", color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Defaults.GROQ_TTS_MALE_VOICES.forEach { v -> FilterChip(groqTtsVoice == v, { groqTtsVoice = v; groqPreviewMsg = "" }, label = { Text(v) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !groqPreviewing && groq.isNotBlank(), onClick = {
                    save()
                    groqPreviewing = true
                    groqPreviewMsg = "Generating sample…"
                    scope.launch {
                        val err = Voice.previewGroq(ctx, groqTtsVoice)
                        groqPreviewMsg = if (err == null) "Groq voice works." else "Groq voice FAILED: $err"
                        groqPreviewing = false
                        groqTick++
                    }
                }) { Text("PREVIEW") }
                if (groqPreviewing) TextButton(onClick = { Voice.stop() }) { Text("STOP") }
            }
            if (groqPreviewMsg.isNotBlank()) Text(groqPreviewMsg, color = if (groqPreviewMsg.contains("FAILED")) Color(0xFFFFC48A) else Dim, fontSize = 12.sp)
            key(groqTick) {
                GroqApi.termsHint(settings)?.let { hint ->
                    Text(hint, color = Color(0xFFFFC48A), fontSize = 12.sp)
                    OutlinedButton(onClick = { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Defaults.GROQ_TERMS_URL))) }) { Text("OPEN GROQ CONSOLE") }
                }
            }
            SwitchRow("Allow interrupting him", "Speak over Jarvis to stop him. Off by default here: the speaker is not echo-cancelled in this mode, " +
                "so he may interrupt himself. When off, the microphone is ignored while he talks.", hfBargeIn) { hfBargeIn = it }
            OutlinedTextField(groqVoiceModel, { groqVoiceModel = it }, Modifier.fillMaxWidth(), label = { Text("Groq model for conversation") }, singleLine = true)
            OutlinedTextField(groqSttModel, { groqSttModel = it }, Modifier.fillMaxWidth(), label = { Text("Groq Whisper model") }, singleLine = true)
            OutlinedTextField(groqTtsModel, { groqTtsModel = it }, Modifier.fillMaxWidth(), label = { Text("Groq voice model") }, singleLine = true)
            GroqStatusPanel(settings)

            Spacer(Modifier.height(6.dp))
            Text("After a call", color = Cyan, fontSize = 13.sp)
            Text("After a call, keep listening", color = Dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfterCall.entries.forEach { a -> FilterChip(afterCall == a, { afterCall = a }, label = { Text(a.label) }) }
            }
            NumberField(postIdle, { postIdle = it }, "End after this many silent seconds (10-300)", Modifier.fillMaxWidth())
            Hint("When you answer a call from Jarvis (task finished, timer, PC message...) and he has said his piece, he stays on the line and " +
                "listens, using the conversation mode chosen above. A task given with \"then I'll give you the next one\" always does this. " +
                "End it with the END button on the call screen or in the notification.")
        }

        // ---------------------------------------------------------------- WEB SEARCH
        SectionHeader("WEB SEARCH", isOpen("WEB")) { toggle("WEB") }
        if (isOpen("WEB")) {
            SwitchRow("Web search", "Tries in order: Gemini Google Search (free only for older keys that can still use Gemini 2.5), " +
                "then Groq browser search (free, about 1,000 searches a day, needs the Groq key), then Wikipedia (facts only).", webSearch) { webSearch = it }
            Hint("Provider order and status (after SAVE):")
            key(searchTick) {
                WebSearch.status(settings).forEachIndexed { i, (name, st) ->
                    Text("${i + 1}. $name: $st", color = if (st.startsWith("skipped") || st == "no key") Color(0xFFFFC48A) else Dim, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = { settings.clearBlockedSearchModels(); searchTick++ }) { Text("RETRY BLOCKED MODELS") }
            OutlinedTextField(groqSearch, { groqSearch = it }, Modifier.fillMaxWidth(), label = { Text("Groq search model") }, singleLine = true)
            OutlinedTextField(groqSearchFb, { groqSearchFb = it }, Modifier.fillMaxWidth(), label = { Text("Groq search backup model (blank = none)") }, singleLine = true)
            Hint("The Gemini search model is under ADVANCED. A model that Google refuses (for example \"no longer available to new users\") " +
                "is skipped for 7 days; changing its name or tapping RETRY gives it another chance.")
        }

        // ---------------------------------------------------------------- v4 NOTIFICATIONS
        SectionHeader("NOTIFICATIONS", isOpen("NOTIF")) { toggle("NOTIF") }
        if (isOpen("NOTIF")) {
            if (BuildConfig.NOTIF_READER) NotificationsSection(settings, resumeTick)
            else Hint("Not included in this build because Google Play Protect blocks installing it. See the README for the full build.")
        }

        // ---------------------------------------------------------------- v4 WATCHERS
        SectionHeader("WEB WATCHERS", isOpen("WATCH")) { toggle("WATCH") }
        if (isOpen("WATCH")) WatchersSection(settings)

        // ---------------------------------------------------------------- MEMORY
        SectionHeader("MEMORY", isOpen("MEMORY")) { toggle("MEMORY") }
        if (isOpen("MEMORY")) {
            Hint("Things Jarvis remembers about you. Notes and lists are on the main screen (NOTES).")
            OutlinedButton(onClick = openMemory) { Text("MEMORY (${Memories.items.size})") }
        }

        // ---------------------------------------------------------------- CALENDAR
        SectionHeader("CALENDAR", isOpen("CAL")) { toggle("CAL") }
        if (isOpen("CAL")) {
            val calOk = remember(calTick, resumeTick) { DeviceCalendar.canRead(ctx) && DeviceCalendar.canWrite(ctx) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (calOk) "Calendar: allowed" else "Calendar: not allowed", color = Color(0xFFDCEBF0), modifier = Modifier.weight(1f))
                if (!calOk) TextButton(onClick = {
                    calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }) { Text("ALLOW") }
            }
            Hint("Needed for \"what's on tomorrow\", adding events, the morning briefing and event heads-ups.")
        }

        // ---------------------------------------------------------------- MORNING CALL
        SectionHeader("MORNING CALL", isOpen("MORNING")) { toggle("MORNING") }
        if (isOpen("MORNING")) {
            SwitchRow("Morning call", "Jarvis rings at this time and reads a short briefing: date, weather, today's events, " +
                "timers, PC messages overnight and open notes. If you decline or miss it, the briefing appears as a notification.",
                morningOn) { morningOn = it }
            OutlinedTextField(morningTime, { morningTime = it.take(5) }, Modifier.fillMaxWidth(), label = { Text("Time (HH:MM, 24-hour)") },
                singleLine = true, isError = parseTime(morningTime) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_NAMES.forEachIndexed { i, d ->
                    val bit = 1 shl i
                    FilterChip(morningDays and bit != 0, { morningDays = morningDays xor bit }, label = { Text(d, fontSize = 11.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(city, { city = it }, Modifier.weight(1f), label = { Text("City for weather (e.g. Kyiv)") }, singleLine = true)
                TextButton(onClick = {
                    cityMsg = "Looking up…"
                    val c = city
                    scope.launch {
                        cityMsg = withContext(Dispatchers.IO) {
                            runCatching { Weather.geocode(c) }.fold({ g ->
                                settings.city = c; settings.cityGeo = g
                                "Found: " + g.split(',').drop(2).joinToString(",")
                            }, { "Not found: ${it.message}" })
                        }
                    }
                }) { Text("CHECK") }
            }
            if (cityMsg.isNotBlank()) Hint(cityMsg)
            Hint("Weather data by Open-Meteo.com (free, CC BY 4.0). No location permission is used.")
            OutlinedButton(onClick = { save(); Morning.ringNow(ctx) }) { Text("TRY THE MORNING CALL NOW") }
        }

        // ---------------------------------------------------------------- REMINDERS
        SectionHeader("REMINDERS", isOpen("REMIND")) { toggle("REMIND") }
        if (isOpen("REMIND")) {
            SwitchRow("Keep ringing until acknowledged", "If a timer/reminder call is declined or not answered, Jarvis rings again. " +
                "Tap DONE on the call screen or notification to stop.", nagOn) { nagOn = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(nagMinutes, { nagMinutes = it }, "Every (min)", Modifier.weight(1f))
                NumberField(nagTimes, { nagTimes = it }, "Up to (times)", Modifier.weight(1f))
            }
        }

        // ---------------------------------------------------------------- PROACTIVE
        SectionHeader("PROACTIVE", isOpen("PRO")) { toggle("PRO") }
        if (isOpen("PRO")) {
            SwitchRow("Upcoming events", "A notification before calendar events (needs calendar access).", eventAlerts) { eventAlerts = it }
            NumberField(eventMinutes, { eventMinutes = it }, "Minutes before", Modifier.fillMaxWidth())
            SwitchRow("Say it aloud", "Only when the app is open or Live mode is running.", eventSpeak) { eventSpeak = it }
            SwitchRow("Low battery", "A short notification when the battery runs low.", batteryAlerts) { batteryAlerts = it }
            NumberField(batteryLevel, { batteryLevel = it }, "Battery %", Modifier.fillMaxWidth())
        }

        // ---------------------------------------------------------------- PC LINK
        SectionHeader("PC LINK", isOpen("PC")) { toggle("PC") }
        if (isOpen("PC")) {
            Hint("Lets your computer (or Claude Code) make Jarvis call you, through the free ntfy.sh service. " +
                "Keeps a connection open in the background, which uses some extra battery. " +
                "The topic name below works like a password: anyone who knows it can make your phone ring and read " +
                "your pings. Never share it, never put it on GitHub, and don't put secrets in ping messages.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PC link", color = Color(0xFFDCEBF0))
                    Text("Status: ${if (pcLink) pcStatus else "off"}", color = Dim, fontSize = 12.sp)
                }
                Switch(pcLink, {
                    pcLink = it
                    save()
                    settings.pcLink = it
                    if (it) PcLinkService.start(ctx) else PcLinkService.stop(ctx)
                })
            }
            OutlinedTextField(topic, { topic = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(64) },
                Modifier.fillMaxWidth(), label = { Text("Topic (secret)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Jarvis topic", topic))
                }) { Text("COPY") }
                OutlinedButton(onClick = { topic = newTopic() }) { Text("NEW TOPIC") }
            }
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true)
            Hint("After changing the topic or server, tap Save and turn PC link off and on. Setup for the PC: see the pc folder on the GitHub page.")
            PcRemoteSection(settings)
        }

        // ---------------------------------------------------------------- v4 DAILY WRAP-UP
        SectionHeader("DAILY WRAP-UP", isOpen("WRAP")) { toggle("WRAP") }
        if (isOpen("WRAP")) WrapSection(settings)

        // ---------------------------------------------------------------- v4 BATTERY
        SectionHeader("BATTERY (SAMSUNG)", isOpen("BATT")) { toggle("BATT") }
        if (isOpen("BATT")) BatterySection(resumeTick)

        // ---------------------------------------------------------------- DIAGNOSTICS
        SectionHeader("DIAGNOSTICS", isOpen("DIAG")) { toggle("DIAG") }
        if (isOpen("DIAG")) {
            Hint("If something does not work, run the matching test and tap COPY to send the exact result.")
            Diagnostics(settings) { save() }
        }

        // ---------------------------------------------------------------- ADVANCED
        SectionHeader("ADVANCED: MODEL NAMES", isOpen("ADV")) { toggle("ADV") }
        if (isOpen("ADV")) {
            Hint("Change only if a model is renamed or retired.")
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Gemini model") }, singleLine = true)
            OutlinedTextField(fallback, { fallback = it }, Modifier.fillMaxWidth(), label = { Text("Gemini backup model (blank = none)") }, singleLine = true)
            Hint("Live model names are under LIVE MODE.")
            OutlinedTextField(searchModel, { searchModel = it }, Modifier.fillMaxWidth(), label = { Text("Web search model") }, singleLine = true)
            OutlinedTextField(tts, { tts = it }, Modifier.fillMaxWidth(), label = { Text("Gemini voice model") }, singleLine = true)
            OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth(), label = { Text("Gemini voice name") }, singleLine = true)
            OutlinedTextField(groqModel, { groqModel = it }, Modifier.fillMaxWidth(), label = { Text("Groq model") }, singleLine = true)
            TextButton(onClick = {
                model = Defaults.GEMINI_MODEL; fallback = Defaults.GEMINI_FALLBACK_MODEL; tts = Defaults.GEMINI_TTS_MODEL
                voice = Defaults.GEMINI_TTS_VOICE; groqModel = Defaults.GROQ_MODEL; searchModel = Defaults.SEARCH_MODEL
                style = Defaults.TTS_STYLE; liveModels = Defaults.LIVE_MODELS.joinToString("\n")
            }) { Text("RESET MODELS AND VOICE TO DEFAULTS") }
        }

        PermissionBanners(resumeTick)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save(); saveMsg = "Saved."; if (settings.geminiKey.isNotBlank()) close() }, enabled = gemini.isNotBlank()) { Text("SAVE") }
            if (settings.geminiKey.isNotBlank()) OutlinedButton(onClick = close) { Text("BACK") }
            TextButton(onClick = { Store.clearMessages() }) { Text("CLEAR CHAT", color = Dim) }
        }
        if (saveMsg.isNotBlank()) Hint(saveMsg)
        Hint("Jarvis ${BuildConfigInfo.version(ctx)}")
        Spacer(Modifier.height(40.dp))
    }
}

/** App version for the Settings footer and diagnostics. */
object BuildConfigInfo {
    fun version(ctx: android.content.Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}

@Composable
private fun Diagnostics(settings: Settings, saveFirst: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    DiagRow("Gemini text", saveFirst) {
        withContext(Dispatchers.IO) {
            Brain.generate(Settings(app), "Reply with exactly: OK", listOf("user" to "ping"), json = false).trim().take(200)
        }
    }
    DiagRow("Gemini voice", saveFirst) {
        val s = Settings(app)
        Voice.preview(app, s.ttsVoice, s.ttsStyle)?.let { throw IllegalStateException(it) }
        "voice ${s.ttsVoice} played"
    }
    DiagRow("Live connection", saveFirst) {
        val s = Settings(app)
        withContext(Dispatchers.IO) {
            // Models skipped for quota are not probed (each probe opens a session); RETRY in Live mode clears them.
            val results = s.liveModels.map { m -> m to (s.liveSkipReason(m)?.let { "skipped: $it" } ?: LiveErrors.probe(s, m)) }
            val u = LiveUsage.today(s)
            val text = results.joinToString("\n") { (m, r) -> "$m: " + (r ?: "OK (setupComplete)") } +
                (if (u.err.isNotBlank()) "\nLast Live error (${u.errModel}, ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(u.errAt))}): ${u.err}" else "") +
                "\nToday: audio sent ${LiveUsage.fmt(u.sentMs)} of ${LiveUsage.fmt(u.sessionMs)} in Live, ${u.sessions} session(s); " +
                "quota saver ${if (s.liveQuotaSaver) "on" else "off"}"
            if (results.none { it.second == null }) throw IllegalStateException(text)
            text
        }
    }
    DiagRow("Groq text", saveFirst) {
        val s = Settings(app)
        if (s.groqKey.isBlank()) throw IllegalStateException("No Groq API key saved.")
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = GroqApi.chat(s, s.groqVoiceModel, "Reply with exactly: OK", listOf("user" to "ping")).trim().take(100)
            "model ${s.groqVoiceModel}: \"$r\" in ${System.currentTimeMillis() - t0} ms"
        }
    }
    DiagRow("Groq Whisper (speak for 3 s)", saveFirst) {
        val s = Settings(app)
        if (s.groqKey.isBlank()) throw IllegalStateException("No Groq API key saved.")
        withContext(Dispatchers.IO) {
            val rec = if (LiveSession.active) null else GroqDiag.record(app, 3)
            val pcm = rec ?: GroqDiag.tone(2.0)
            val t0 = System.currentTimeMillis()
            val text = GroqApi.transcribe(s, Wav.encode(pcm, 16000), pcm.size / 32000.0)
            "model ${s.groqSttModel} answered in ${System.currentTimeMillis() - t0} ms. Source: " +
                (if (rec != null) "microphone" else "generated tone (the microphone was not used: " +
                    (if (LiveSession.active) "a conversation is running" else "no permission or the microphone is busy") + ")") +
                ". Heard: \"$text\""
        }
    }
    DiagRow("Groq voice (Orpheus)", saveFirst) {
        val s = Settings(app)
        Voice.previewGroq(app, s.groqTtsVoice)?.let { throw IllegalStateException(it) }
        "voice ${s.groqTtsVoice} (${s.groqTtsModel}) played"
    }
    DiagRow("Web search", saveFirst) {
        withContext(Dispatchers.IO) {
            val r = Brain.lookup(Settings(app), "What is the capital of Ukraine?")
            "Answered by: ${r.provider}\n" + (if (r.notes.isNotBlank()) "Earlier providers failed:\n${r.notes}\n" else "") +
                r.text.take(160) + " (${r.sources.size} sources)"
        }
    }
    DiagRow("Weather", saveFirst) {
        withContext(Dispatchers.IO) { Weather.today(Settings(app)) ?: throw IllegalStateException("No city set (Morning call section).") }
    }
    DiagRow("Test call in 5 seconds", saveFirst) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val call = CallInfo(Store.nextId(), "test", "Test call", "")
            Store.putCall(call)
            Caller.ring(app, call)
        }, 5000)
        "ringing in 5 seconds (lock the screen to test the lock-screen call)"
    }
    DiagnosticsV4(saveFirst)
}

@Composable
internal fun DiagRow(label: String, saveFirst: () -> Unit, test: suspend () -> String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFDCEBF0), modifier = Modifier.weight(1f))
            if (result.isNotBlank()) TextButton(onClick = {
                val text = "Jarvis ${BuildConfigInfo.version(ctx)}, Android ${android.os.Build.VERSION.SDK_INT} " +
                    "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n$label: ${if (ok) "OK" else "ERROR"}\n$result"
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Jarvis diagnostics", text))
            }) { Text("COPY", fontSize = 12.sp) }
            Button(enabled = !running, onClick = {
                saveFirst()
                running = true
                result = "running…"
                scope.launch {
                    try {
                        result = test(); ok = true
                    } catch (e: BrainException) {
                        result = "${e.kind}: ${e.message}"; ok = false
                    } catch (e: Exception) {
                        result = "${e.javaClass.simpleName}: ${e.message}"; ok = false
                    } finally {
                        running = false
                    }
                }
            }) { Text("TEST", fontSize = 12.sp) }
        }
        if (result.isNotBlank()) Text((if (running) "" else if (ok) "OK: " else "ERROR: ") + result,
            color = if (ok) Dim else Color(0xFFFFC48A), fontSize = 12.sp)
    }
}

@Composable
private fun VoiceRow(name: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Cyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(name, color = if (selected) Cyan else Color(0xFFDCEBF0), modifier = Modifier.weight(1f))
        Text(tag, color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun MemoryScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MEMORY", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 3.sp,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = back) { Text("BACK") }
        }
        Text("Things Jarvis remembers about you. They are stored only on this phone and sent to Gemini with each request. " +
            "Say \"remember that…\" or \"forget…\" to change them.", color = Dim, fontSize = 12.sp)
        if (Memories.items.isEmpty()) Text("Nothing yet.", color = Dim)
        Memories.items.reversed().forEach { m ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.text, color = Color(0xFFDCEBF0), fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton(onClick = { Memories.delete(ctx, m.id) }) { Text("DELETE", color = Dim, fontSize = 12.sp) }
            }
        }
    }
}

/** v5: per-model status with RETRY, and today's Live usage with the last quota error (COPY). */
@Composable
private fun LiveStatusPanel(settings: Settings) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    key(tick) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
            Text("Models (saved list):", color = Cyan, fontSize = 12.sp)
            LiveModels.status(settings).forEachIndexed { i, (m, st) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. $m: $st", color = if (st == "ready") Dim else Color(0xFFFFC48A), fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    if (st != "ready") TextButton(onClick = { settings.unskipLiveModel(m); tick++ }) { Text("RETRY NOW", fontSize = 11.sp) }
                }
            }
            val u = LiveUsage.today(settings)
            val pct = if (u.sessionMs > 0) " (${u.sentMs * 100 / u.sessionMs}%)" else ""
            Spacer(Modifier.height(6.dp))
            Text("Today (Pacific day ${u.day}):", color = Cyan, fontSize = 12.sp)
            Text("Audio sent ${LiveUsage.fmt(u.sentMs)} of ${LiveUsage.fmt(u.sessionMs)} in Live$pct · ${u.sessions} session(s)" +
                (if (u.model.isNotBlank()) " · last model ${u.model}" else ""), color = Dim, fontSize = 12.sp)
            if (u.err.isNotBlank()) {
                val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(u.errAt))
                val line = "Last Live error ($at, ${u.errModel}): ${u.err}"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, color = Color(0xFFFFC48A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Jarvis Live error",
                            "Jarvis ${BuildConfigInfo.version(ctx)}\n$line\nToday: sent ${LiveUsage.fmt(u.sentMs)} of ${LiveUsage.fmt(u.sessionMs)}, " +
                                "${u.sessions} session(s)\n" + LiveModels.status(settings).joinToString("\n") { "${it.first}: ${it.second}" }))
                    }) { Text("COPY", fontSize = 11.sp) }
                }
            }
            TextButton(onClick = { tick++ }) { Text("REFRESH", fontSize = 11.sp) }
        }
    }
}

/** v6: today's Groq counters and any limit Groq reported (COPY). */
@Composable
private fun GroqStatusPanel(settings: Settings) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    key(tick) {
        val u = GroqUsage.today(settings)
        val blocks = GroqLimits.active()
        val usage = "Groq today (UTC day ${u.day}): ${u.stt} transcription(s), ${"%.0f".format(u.sttSec)} s of audio · ${u.chat} chat call(s) · " +
            "${u.tts} voice call(s), ${u.ttsChars} characters"
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
            Text(usage, color = Dim, fontSize = 12.sp)
            Text("Free limits (Groq docs): Whisper 20 requests/min, 2,000/day, 7,200 s audio/hour, 28,800 s/day (each request counts at least 10 s); " +
                "voice 10 requests/min, 100/day; ${Defaults.GROQ_VOICE_MODEL}: 30 requests/min, 1,000/day.", color = Dim, fontSize = 11.sp)
            blocks.forEach { b ->
                Text("Limit reached: ${b.kind} (${if (b.daily) "daily" else "per minute"}), about ${GroqLimits.minutesLeft(b)} min to go. ${b.why}",
                    color = Color(0xFFFFC48A), fontSize = 12.sp)
            }
            if (u.err.isNotBlank()) {
                val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(u.errAt))
                Text("Last Groq error ($at): ${u.err}", color = Color(0xFFFFC48A), fontSize = 12.sp)
            }
            Row {
                TextButton(onClick = { tick++ }) { Text("REFRESH", fontSize = 11.sp) }
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Jarvis Groq",
                        "Jarvis ${BuildConfigInfo.version(ctx)}\n$usage\n" + blocks.joinToString("\n") { "limit ${it.kind}: ${it.why}" } +
                            (if (u.err.isNotBlank()) "\nlast error: ${u.err}" else "")))
                }) { Text("COPY", fontSize = 11.sp) }
                TextButton(onClick = { GroqLimits.clear(); tick++ }) { Text("RETRY NOW", fontSize = 11.sp) }
            }
        }
    }
}
