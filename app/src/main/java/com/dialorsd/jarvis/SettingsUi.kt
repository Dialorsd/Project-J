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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = Cyan.copy(alpha = 0.25f))
    Text(title, color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp)
}

@Composable
fun SettingsScreen(settings: Settings, resumeTick: Int, close: () -> Unit) {
    var showMemory by remember { mutableStateOf(false) }
    if (showMemory) MemoryScreen { showMemory = false }
    else SettingsMain(settings, resumeTick, close) { showMemory = true }
}

@Composable
private fun SettingsMain(settings: Settings, resumeTick: Int, close: () -> Unit, openMemory: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var lang by remember { mutableStateOf(settings.speechLang) }
    var mode by remember { mutableStateOf(settings.voiceMode) }
    var humour by remember { mutableStateOf(settings.humour) }
    var showVoices by remember { mutableStateOf(false) }
    var previewMsg by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var pcLink by remember { mutableStateOf(settings.pcLink) }
    var topic by remember { mutableStateOf(settings.ntfyTopic) }
    var server by remember { mutableStateOf(settings.ntfyServer) }
    var calTick by remember { mutableStateOf(0) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { calTick++ }
    val pcStatus by PcLinkService.status

    fun save() {
        settings.geminiKey = gemini; settings.groqKey = groq
        settings.model = model; settings.fallbackModel = fallback; settings.ttsModel = tts
        settings.ttsVoice = voice; settings.ttsStyle = style; settings.groqModel = groqModel
        settings.searchModel = searchModel; settings.webSearch = webSearch
        settings.speechLang = lang; settings.voiceMode = mode; settings.humour = humour
        settings.ntfyServer = server
        if (topic.isNotBlank()) settings.ntfyTopic = topic
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SETTINGS", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 3.sp)

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

        OutlinedTextField(gemini, { gemini = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key (required)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(groq, { groq = it }, Modifier.fillMaxWidth(), label = { Text("Groq API key (optional backup)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())

        Section("SPEECH")
        Text("Speech language", color = Dim)
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
        Text("English replies use the Gemini voice; Ukrainian replies use the phone's own voice.", color = Dim, fontSize = 12.sp)

        Text("Humour: " + when {
            humour < 0.2f -> "professional"; humour < 0.45f -> "polite, a little dry"
            humour < 0.75f -> "dry wit"; else -> "sarcastic"
        }, color = Dim)
        Slider(humour, { humour = it })

        Section("ABILITIES")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Web search", color = Color(0xFFDCEBF0))
                Text("Google Search through Gemini 2.5 Flash (free: about 500 searches a day).", color = Dim, fontSize = 12.sp)
            }
            Switch(webSearch, { webSearch = it })
        }
        val calOk = remember(calTick, resumeTick) { DeviceCalendar.canRead(ctx) && DeviceCalendar.canWrite(ctx) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (calOk) "Calendar: allowed" else "Calendar: not allowed", color = Color(0xFFDCEBF0), modifier = Modifier.weight(1f))
            if (!calOk) TextButton(onClick = {
                calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }) { Text("ALLOW") }
        }
        OutlinedButton(onClick = openMemory) { Text("MEMORY (${Memories.items.size})") }

        Section("PC LINK")
        Text(
            "Lets your computer (or Claude Code) make Jarvis call you, through the free ntfy.sh service. " +
                "Keeps a connection open in the background, which uses some extra battery. " +
                "The topic name below works like a password: anyone who knows it can make your phone ring and read " +
                "your pings. Never share it, never put it on GitHub, and don't put secrets in ping messages.",
            color = Dim, fontSize = 12.sp
        )
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
        Text("After changing the topic or server, tap Save and turn PC link off and on. Setup for the PC: see the pc folder on the GitHub page.",
            color = Dim, fontSize = 12.sp)

        Section("MODELS")
        Text("Change only if a model is renamed or retired.", color = Dim, fontSize = 12.sp)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Gemini model") }, singleLine = true)
        OutlinedTextField(fallback, { fallback = it }, Modifier.fillMaxWidth(), label = { Text("Gemini backup model (blank = none)") }, singleLine = true)
        OutlinedTextField(searchModel, { searchModel = it }, Modifier.fillMaxWidth(), label = { Text("Web search model") }, singleLine = true)
        OutlinedTextField(tts, { tts = it }, Modifier.fillMaxWidth(), label = { Text("Gemini voice model") }, singleLine = true)
        OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth(), label = { Text("Gemini voice name") }, singleLine = true)
        OutlinedTextField(groqModel, { groqModel = it }, Modifier.fillMaxWidth(), label = { Text("Groq model") }, singleLine = true)
        TextButton(onClick = {
            model = Defaults.GEMINI_MODEL; fallback = Defaults.GEMINI_FALLBACK_MODEL; tts = Defaults.GEMINI_TTS_MODEL
            voice = Defaults.GEMINI_TTS_VOICE; groqModel = Defaults.GROQ_MODEL; searchModel = Defaults.SEARCH_MODEL
            style = Defaults.TTS_STYLE
        }) { Text("RESET MODELS AND VOICE TO DEFAULTS") }

        PermissionBanners(resumeTick)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { save(); if (settings.geminiKey.isNotBlank()) close() }, enabled = gemini.isNotBlank()) { Text("SAVE") }
            if (settings.geminiKey.isNotBlank()) OutlinedButton(onClick = close) { Text("BACK") }
            TextButton(onClick = { Store.clearMessages() }) { Text("CLEAR CHAT", color = Dim) }
        }
        Spacer(Modifier.height(40.dp))
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
