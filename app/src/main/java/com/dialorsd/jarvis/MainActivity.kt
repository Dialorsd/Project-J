package com.dialorsd.jarvis

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        /** From the widget / tile: open straight into push-to-talk or Live mode. */
        const val ACTION_LISTEN = "com.dialorsd.jarvis.LISTEN"
        const val ACTION_LIVE = "com.dialorsd.jarvis.START_LIVE"
        /** One-shot request for the main screen; consumed when handled. */
        val pendingAction = mutableStateOf<String?>(null)
    }

    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Voice.init(this)
        if (savedInstanceState == null) takeAction(intent)
        setContent { JarvisTheme { Root(resumeTick.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeAction(intent)
    }

    private fun takeAction(i: Intent?) {
        val a = i?.action
        if (a == ACTION_LISTEN || a == ACTION_LIVE) pendingAction.value = a
        // v4: "Share -> Jarvis" with a picture (screenshot, photo).
        if (i != null && a == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: return
            val question = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            attachImage(this, uri, question)
        }
    }

    override fun onResume() {
        super.onResume()
        Store.appVisible = true
        resumeTick.intValue++
        HeadsetButton.refresh(this)
        // Cheap re-arming of v3 alarms (calendar may have changed while we were away).
        val app = applicationContext
        Thread { runCatching { Proactive.scheduleEvents(app) }; runCatching { Morning.schedule(app) }; runCatching { Wrap.schedule(app) } }.start()
    }

    override fun onPause() {
        Store.appVisible = false
        HeadsetButton.refresh(this)
        super.onPause()
    }
}

// ---------------------------------------------------------------- root

/** v4: decode a picture in the background and keep it for the next question. */
fun attachImage(context: Context, uri: Uri, question: String = "") {
    val app = context.applicationContext
    Thread {
        val r = runCatching { Vision.load(app, uri) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            r.onSuccess {
                Vision.pending.value = it
                if (question.isNotBlank()) Jarvis.send(app, question)
                else Store.addMessage("note", "Picture attached. Ask about it: type, or tap the mic. / Зображення додано: запитайте про нього.")
            }.onFailure { Store.addMessage("note", "Could not read that picture: ${it.message}") }
        }
    }.start()
}

@Composable
private fun Root(resumeTick: Int) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var showSettings by remember { mutableStateOf(settings.geminiKey.isBlank()) }
    var showNotes by remember { mutableStateOf(false) }
    var showInterp by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Box(Modifier.safeDrawingPadding()) {
            when {
                showSettings -> SettingsScreen(settings, resumeTick) { showSettings = false }
                showNotes -> NotesScreen { showNotes = false }
                showInterp -> InterpreterScreen(settings) { showInterp = false }
                else -> MainScreen(settings, resumeTick, { showSettings = true }, { showNotes = true }, { showInterp = true })
            }
        }
    }
}

// ---------------------------------------------------------------- main screen

@Composable
private fun MainScreen(settings: Settings, resumeTick: Int, openSettings: () -> Unit, openNotes: () -> Unit, openInterp: () -> Unit) {
    val ctx = LocalContext.current
    val orb by Store.orb
    val partial by Store.partialSpeech
    var input by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(settings.speechLang) }
    val listState = rememberLazyListState()

    // Ask for notifications once (Android 13+), needed for the incoming-call screen.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !settings.askedNotifications &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.askedNotifications = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val ear = remember { Ear(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { ear.destroy() } }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ear.start(lang) else Store.addMessage("note", "Microphone permission denied: you can still type.")
    }

    val liveMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) LiveSession.start(ctx) else Store.addMessage("note", "Live mode needs the microphone permission.")
    }
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun startLive() { if (hasMic()) LiveSession.start(ctx) else liveMicLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    fun toggleListen() {
        when {
            Store.orb.value == OrbState.LISTENING -> ear.stop()
            Store.orb.value == OrbState.SPEAKING -> Voice.stop()
            hasMic() -> ear.start(lang)
            else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Headset button toggles listening while the app is open.
    DisposableEffect(Unit) {
        HeadsetButton.onPress = { toggleListen() }
        onDispose { HeadsetButton.onPress = null }
    }
    // Widget / Quick Settings tile requests.
    val pending by MainActivity.pendingAction
    LaunchedEffect(pending) {
        when (pending) {
            MainActivity.ACTION_LISTEN -> if (!LiveSession.active) { if (hasMic()) ear.start(lang) else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            MainActivity.ACTION_LIVE -> if (!LiveSession.active) startLive()
        }
        if (pending != null) MainActivity.pendingAction.value = null
    }
    val liveOn by LiveSession.running
    val liveStatus by LiveSession.status
    val liveMuted by LiveSession.muted

    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    DisposableEffect(Unit) {
        Jarvis.requestCalendar = {
            calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
        onDispose { Jarvis.requestCalendar = null }
    }

    // v4: camera (system camera app writes into our cache through the FileProvider) and photo picker (no permission).
    val cameraUri = remember { runCatching { Vision.cameraUri(ctx) }.getOrNull() }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && cameraUri != null) attachImage(ctx, cameraUri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachImage(ctx, uri)
    }
    val pendingImage by Vision.pending

    LaunchedEffect(Store.messages.size) {
        if (Store.messages.isNotEmpty()) listState.animateScrollToItem(Store.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        // header
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("J.A.R.V.I.S", color = Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = true, onClick = {
                lang = SpeechLang.entries[(lang.ordinal + 1) % SpeechLang.entries.size]
                settings.speechLang = lang
            }, label = { Text(lang.label, fontFamily = FontFamily.Monospace) })
            TextButton(onClick = openNotes) { Text("NOTES", color = Dim, fontFamily = FontFamily.Monospace) }
            TextButton(onClick = openSettings) { Text("SETTINGS", color = Dim, fontFamily = FontFamily.Monospace) }
        }

        PermissionBanners(resumeTick)

        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Orb(orb, Modifier.size(180.dp))
        }
        Text(
            when (orb) {
                OrbState.IDLE -> "STANDING BY"; OrbState.LISTENING -> "LISTENING"
                OrbState.THINKING -> "THINKING"; OrbState.SPEAKING -> "SPEAKING"
            },
            color = Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 4.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // Live mode controls
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            if (liveOn) {
                Text(if (liveMuted) "MIC MUTED" else liveStatus.uppercase(), color = Cyan, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { LiveSession.toggleMute() }) { Text(if (liveMuted) "UNMUTE" else "MUTE", color = Dim) }
                Button(onClick = { LiveSession.stop(ctx) }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE5484D), contentColor = Color.White)) { Text("END") }
            } else {
                OutlinedButton(onClick = { startLive() }) { Text(if (settings.handsFreeDefault) "HANDS-FREE CONVERSATION" else "LIVE CONVERSATION") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = openInterp) { Text("INTERPRETER", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }

        TimerList()
        WatcherList()

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Store.messages) { m -> Bubble(m) }
            if (partial.isNotBlank()) item { Bubble(Msg("user", "$partial…")) }
        }

        // v4: picture row (camera / gallery / attached picture)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (cameraUri == null) Store.addMessage("note", "Camera is not available.")
                else runCatching { camera.launch(cameraUri) }.onFailure { Store.addMessage("note", "No camera app found.") }
            }) { Text("CAMERA", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            TextButton(onClick = {
                runCatching { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { Store.addMessage("note", "Could not open the photo picker: ${it.message}") }
            }) { Text("GALLERY", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            pendingImage?.let { p ->
                Thumb(p.thumbPath, Modifier.size(44.dp))
                TextButton(onClick = { Vision.pending.value = null }) { Text("REMOVE", color = Dim, fontSize = 11.sp) }
            }
        }

        // input row
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (pendingImage != null) "Ask about the picture…" else "Type to Jarvis…") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { Jarvis.send(ctx, input); input = "" }),
            )
            Spacer(Modifier.width(8.dp))
            if (input.isNotBlank() || pendingImage != null) {
                Button(onClick = { Jarvis.send(ctx, input); input = "" }) { Text("SEND") }
            } else {
                val listening = orb == OrbState.LISTENING
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(if (listening) Cyan else Panel)
                        .border(2.dp, Cyan, CircleShape)
                        .clickable { if (liveOn) LiveSession.toggleMute() else toggleListen() },
                    contentAlignment = Alignment.Center
                ) { MicGlyph(if (listening) Color.Black else Cyan, Modifier.size(30.dp)) }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    if (m.role == "sources") { SourcesBubble(m); return }
    if (m.role == "image") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) { Thumb(m.text, Modifier.size(140.dp)) }
        return
    }
    val (bg, fg, align) = when (m.role) {
        "user" -> Triple(Color(0xFF12303A), Color(0xFFE6F6FA), Alignment.End)
        "jarvis" -> Triple(Panel, Color(0xFFDCEBF0), Alignment.Start)
        else -> Triple(Color.Transparent, Dim, Alignment.CenterHorizontally)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            m.text, color = fg, fontSize = if (m.role == "note") 12.sp else 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
                .then(if (m.role == "jarvis") Modifier.border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SourcesBubble(m: Msg) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text("SOURCES", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
        m.text.lines().filter { it.contains('\t') }.forEach { line ->
            val title = line.substringBefore('\t')
            val url = line.substringAfter('\t')
            Text(title, color = Cyan, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.clickable { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun TimerList() {
    val ctx = LocalContext.current
    if (Store.timers.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Store.timers.sortedBy { if (it.kind == "task") Long.MAX_VALUE else it.endAt }.forEach { t ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val left = if (t.kind == "task") "working…" else Timers.formatDuration(((t.endAt - now) / 1000).coerceAtLeast(0L))
                Text(left, color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Text(t.label, color = Color(0xFFDCEBF0), maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = { Timers.cancel(ctx, t.id) }) { Text("CANCEL", color = Dim, fontSize = 12.sp) }
            }
        }
    }
}

/** v4: picture thumbnail from a file in app storage. */
@Composable
fun Thumb(path: String, modifier: Modifier) {
    val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    if (bmp == null) { Text("(picture)", color = Dim, fontSize = 12.sp); return }
    androidx.compose.foundation.Image(bmp, contentDescription = "picture", modifier = modifier.clip(RoundedCornerShape(8.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

/** v4: active watchers under the timers, with last check and a cancel button. */
@Composable
private fun WatcherList() {
    val ctx = LocalContext.current
    if (Watchers.items.isEmpty()) return
    val f = remember { java.text.SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Watchers.items.forEach { w ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Cyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text("WATCH · " + w.label, color = Color(0xFFDCEBF0), maxLines = 1, fontSize = 14.sp)
                    Text("every ${w.intervalMin} min" + (if (w.lastCheck > 0) " · ${f.format(java.util.Date(w.lastCheck))}: ${w.lastResult}" else ""),
                        color = Dim, fontSize = 11.sp, maxLines = 1)
                }
                TextButton(onClick = { Thread { Watchers.remove(ctx.applicationContext, w.id) }.start() }) { Text("CANCEL", color = Dim, fontSize = 12.sp) }
            }
        }
    }
}

/** Warnings for permissions that break the "he calls me" feature, with buttons to the system screens. */
@Composable
fun PermissionBanners(resumeTick: Int) {
    val ctx = LocalContext.current
    // resumeTick forces a re-check whenever the user comes back from system settings.
    val notif = remember(resumeTick) { Caller.canNotify(ctx) }
    val exact = remember(resumeTick) { Timers.canExact(ctx) }
    val full = remember(resumeTick) { Caller.canFullScreen(ctx) }
    Column {
        if (!notif) Banner("Notifications are off: Jarvis cannot call you.", "ALLOW") {
            open(ctx, Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        if (!full) Banner("Full-screen calls are not allowed: calls will appear only as notifications.", "ALLOW") {
            if (Build.VERSION.SDK_INT >= 34) open(ctx, Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
        }
        if (!exact) Banner("Exact alarms are off: timers may ring late.", "ALLOW") {
            if (Build.VERSION.SDK_INT >= 31) open(ctx, Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
        }
    }
}

@Composable
private fun Banner(text: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A1A0A)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color(0xFFFFC48A), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        TextButton(onClick = onClick) { Text(button, color = Cyan) }
    }
}

fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// ---------------------------------------------------------------- hearing

/** Wraps Android SpeechRecognizer. Must be used on the main thread. */
private class Ear(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null

    fun start(lang: SpeechLang) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Store.addMessage("note", "Speech recognition is not available on this phone. Typing still works.")
            return
        }
        Voice.stop()
        destroy()
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) {
            Store.addMessage("note", "Could not start speech recognition. Typing still works.")
            return
        }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Store.setOrb(OrbState.LISTENING) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    Store.partialSpeech.value = it
                }
            }
            override fun onResults(results: Bundle?) {
                Store.partialSpeech.value = ""
                Store.setOrb(OrbState.IDLE)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) Jarvis.send(ctx, text)
            }
            override fun onError(error: Int) {
                Store.partialSpeech.value = ""
                Store.setOrb(OrbState.IDLE)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // heard nothing
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> "Speech recognition error ($error). Typing still works."
                }
                msg?.let { Store.addMessage("note", it) }
            }
        })
        val tag = when (lang) {
            SpeechLang.EN -> "en-GB"
            SpeechLang.UK -> "uk-UA"
            SpeechLang.AUTO -> Locale.getDefault().toLanguageTag()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            Store.setOrb(OrbState.LISTENING)
            r.startListening(intent)
        } catch (e: Exception) {
            Store.setOrb(OrbState.IDLE)
            Store.addMessage("note", "Could not start listening. Typing still works.")
        }
    }

    fun stop() {
        runCatching { rec?.stopListening() }
    }

    fun destroy() {
        runCatching { rec?.destroy() }
        rec = null
        if (Store.orb.value == OrbState.LISTENING) Store.setOrb(OrbState.IDLE)
    }
}
