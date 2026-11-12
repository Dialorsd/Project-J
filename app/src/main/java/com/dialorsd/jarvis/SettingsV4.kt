package com.dialorsd.jarvis

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* v4 Settings sections. Unlike the older sections, these save immediately when changed. */

private val Light = Color(0xFFDCEBF0)

fun copyText(ctx: Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    // Android 13+: keeps the secret out of the clipboard preview.
    if (sensitive) clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

// ---------------------------------------------------------------- notifications

@Composable
fun NotificationsSection(settings: Settings, resumeTick: Int) {
    val ctx = LocalContext.current
    val access = remember(resumeTick) { Notifs.hasAccess(ctx) }
    var allowed by remember { mutableStateOf(settings.notifAllowed) }
    var announce by remember { mutableStateOf(settings.notifAnnounce) }
    fun setAllowed(v: Set<String>) { allowed = v; settings.notifAllowed = v; Notifs.dropDisallowed(ctx) }

    Hint("Lets Jarvis read your recent notifications (Gmail, Telegram...) when you ask, e.g. \"any new messages?\". " +
        "PRIVACY: nothing is kept until you tick apps below. The text of ticked apps is stored on this phone for 24 hours " +
        "(max 100) and sent to Google Gemini ONLY when you ask about your notifications, never with every question. " +
        "Gemini's free tier may use what is sent to improve Google's products and people may review it, so don't tick apps " +
        "whose messages must stay private. Banking, authenticator and password apps and one-time codes are always skipped.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (access) "Notification access: ON" else "Notification access: OFF", color = Light, modifier = Modifier.weight(1f))
        TextButton(onClick = { open(ctx, Notifs.accessIntent(ctx)) }) { Text(if (access) "CHANGE" else "OPEN ACCESS SETTINGS") }
    }
    if (!access) {
        Hint("If the switch is greyed out or says \"Restricted setting\" (normal for apps installed from a file): tap APP INFO, " +
            "then the ⋮ menu (top right) → \"Allow restricted settings\", confirm, come back and try again.")
        OutlinedButton(onClick = { open(ctx, Notifs.appInfoIntent(ctx)) }) { Text("APP INFO") }
    }
    Text("Apps Jarvis may read (${allowed.size})", color = Dim)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { setAllowed(allowed + Notifs.MESSAGING.keys) }) { Text("ALLOW MESSAGING & MAIL APPS", fontSize = 12.sp) }
        if (allowed.isNotEmpty()) TextButton(onClick = { setAllowed(emptySet()) }) { Text("NONE", color = Dim) }
    }
    // Apps that have posted something, plus anything already allowed.
    val apps = (Notifs.seenApps.keys + allowed).distinct().filter { !Notifs.isAlwaysDenied(it) }
        .sortedBy { (Notifs.seenApps[it] ?: Notifs.MESSAGING[it] ?: it).lowercase() }
    if (apps.isEmpty()) Hint("The list fills as apps post notifications (after access is ON).")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)) {
        apps.forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pkg in allowed, { on -> setAllowed(if (on) allowed + pkg else allowed - pkg) })
                Column(Modifier.weight(1f)) {
                    Text(Notifs.seenApps[pkg] ?: Notifs.MESSAGING[pkg] ?: pkg, color = Light, fontSize = 14.sp)
                    Text(pkg, color = Dim, fontSize = 10.sp)
                }
            }
        }
    }
    SwitchRow("Announce in Live mode", "While Live mode runs, say new notifications from ticked apps aloud " +
        "(their text is then sent to Gemini as they arrive).", announce) { announce = it; settings.notifAnnounce = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Stored now: ${Notifs.items.size}", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { Notifs.clear(ctx) }) { Text("DELETE STORED", color = Dim, fontSize = 12.sp) }
    }
}

// ---------------------------------------------------------------- watchers

@Composable
fun WatchersSection(settings: Settings) {
    val ctx = LocalContext.current
    var wifi by remember { mutableStateOf(settings.watchWifiOnly) }
    Hint("Say e.g. \"call me when example.com is back up\", \"tell me when this page says 'in stock'\" or \"call me when the " +
        "price on <address> drops below 500\". Each watcher loads the page once every 30 minutes (15 at least) and rings you " +
        "when it happens, then stops (unless you asked to keep watching). Up to 10. Number watchers only work on simple " +
        "pages that show the number in their HTML (not prices loaded by scripts). Active watchers are shown on the main screen.")
    SwitchRow("Only on Wi-Fi", "Checks wait for Wi-Fi (saves mobile data).", wifi) {
        wifi = it; settings.watchWifiOnly = it
        Thread { Watchers.rescheduleAll(ctx.applicationContext) }.start()
    }
}

// ---------------------------------------------------------------- PC remote

@Composable
fun PcRemoteSection(settings: Settings) {
    val ctx = LocalContext.current
    var paired by remember { mutableStateOf(PcRemote.paired(settings)) }
    var code by remember { mutableStateOf("") } // shown once, right after pairing
    var confirmRepair by remember { mutableStateOf(false) }
    val ps = "mkdir \$HOME\\jarvis -Force; cd \$HOME\\jarvis; Invoke-WebRequest https://raw.githubusercontent.com/Dialorsd/Project-J/main/pc/jarvis-listen.ps1 -OutFile jarvis-listen.ps1; powershell -ExecutionPolicy Bypass -File .\\jarvis-listen.ps1 -Pair"

    Spacer6()
    Text("PC REMOTE", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp)
    Hint("Ask Jarvis \"how is my PC?\", \"lock my PC\", \"tell me when the render finishes\" or \"shut down the PC when the " +
        "download is done\". The PC helper (jarvis-listen) only runs commands YOU enabled in its config file on the PC, " +
        "and every request is signed with a secret shared only by this phone and your PC, so knowing the topic is not enough. " +
        "Shutdown, restart, sleep and your own commands are off on the PC until you switch them on there. PC link must be on.")
    Text(if (paired) "Status: paired" else "Status: not paired", color = Light)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (paired && !confirmRepair) { confirmRepair = true; return@OutlinedButton }
            confirmRepair = false
            code = PcRemote.pair(settings); paired = true
        }) { Text(if (confirmRepair) "TAP AGAIN: NEW SECRET" else if (paired) "PAIR AGAIN" else "PAIR PC") }
        if (paired) TextButton(onClick = { PcRemote.unpair(settings); paired = false; code = "" }) { Text("UNPAIR", color = Dim) }
    }
    if (confirmRepair) Hint("Pairing again makes a new secret: the PC stops obeying until you paste the new code there.")
    if (code.isNotBlank()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(8.dp)) {
            Text("Pairing code (shown only now; it contains the secret - send it only to your own PC, never post it):",
                color = Color(0xFFFFC48A), fontSize = 12.sp)
            Text(code, color = Light, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row {
                TextButton(onClick = { copyText(ctx, "Jarvis pairing code", code, sensitive = true) }) { Text("COPY CODE") }
                TextButton(onClick = { code = "" }) { Text("HIDE", color = Dim) }
            }
            Text("On Windows: open PowerShell, paste this command (COPY COMMAND), press Enter, then paste the code when asked:",
                color = Dim, fontSize = 12.sp)
            Text(ps, color = Light, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            TextButton(onClick = { copyText(ctx, "Jarvis PC command", ps) }) { Text("COPY COMMAND") }
            Hint("Then: powershell -ExecutionPolicy Bypass -File \$HOME\\jarvis\\jarvis-listen.ps1 -InstallStartup  (starts it hidden at login). " +
                "Mac/Linux: see pc/README.md. If you changed the PC link topic or server, pair again.")
        }
    }
}

@Composable
private fun Spacer6() = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))

// ---------------------------------------------------------------- daily wrap-up

@Composable
fun WrapSection(settings: Settings) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(settings.wrapOn) }
    var call by remember { mutableStateOf(settings.wrapCall) }
    var time by remember { mutableStateOf("%02d:%02d".format(settings.wrapTime / 60, settings.wrapTime % 60)) }
    fun parse(t: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").find(t) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
    fun resched() { val app = ctx.applicationContext; Thread { runCatching { Wrap.schedule(app) } }.start() }
    SwitchRow("Daily wrap-up", "In the evening Jarvis posts a short summary: what finished today (timers, tasks, PC messages, " +
        "watchers), what is still running, open list items, tomorrow's events and weather. You can also ask \"what happened today?\".",
        on) { on = it; settings.wrapOn = it; resched() }
    OutlinedTextField(time, { t -> time = t.take(5); parse(time)?.let { settings.wrapTime = it; resched() } }, Modifier.fillMaxWidth(),
        label = { Text("Time (HH:MM, 24-hour)") }, singleLine = true, isError = parse(time) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    SwitchRow("Ring instead", "Call you (like the morning call) instead of only a notification.", call) { call = it; settings.wrapCall = it }
    OutlinedButton(onClick = { Wrap.runNow(ctx.applicationContext) }) { Text("WRAP-UP NOW") }
}

// ---------------------------------------------------------------- battery

@Composable
fun BatterySection(resumeTick: Int) {
    val ctx = LocalContext.current
    val unrestricted = remember(resumeTick) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    Hint("Samsung phones put apps to sleep to save battery, which can stop PC link, PC replies and watchers. " +
        "Set Jarvis to Unrestricted: tap APP INFO → Battery → Unrestricted. Also check Settings → Battery → Background usage " +
        "limits and make sure Jarvis is not in \"Sleeping apps\" or \"Deep sleeping apps\".")
    Text(if (unrestricted) "Battery: unrestricted (good)" else "Battery: optimised (may be put to sleep)", color = Light)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open(ctx, Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) {
            Text("APP INFO")
        }
        TextButton(onClick = { open(ctx, Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("ALL APPS LIST", color = Dim) }
    }
}

// ---------------------------------------------------------------- diagnostics

@Composable
fun DiagnosticsV4(saveFirst: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    DiagRow("Notification access", saveFirst) {
        val s = Settings(app)
        if (!Notifs.hasAccess(app)) throw IllegalStateException("access is OFF (Settings → Notifications → OPEN ACCESS SETTINGS)")
        "access ON, ${s.notifAllowed.size} app(s) allowed, ${Notifs.items.size} stored, ${Notifs.seenApps.size} app(s) seen"
    }
    DiagRow("Watcher check (example.com)", saveFirst) {
        withContext(Dispatchers.IO) {
            val w = Watcher(0, "page_contains", "https://example.com", "Example Domain", null, "test", 30, false, 0)
            val r = Watchers.check(w)
            if (r.state != true) throw IllegalStateException(r.summary)
            r.summary
        }
    }
    DiagRow("Image question", saveFirst) {
        withContext(Dispatchers.IO) {
            val img = Vision.testImage(app)
            val t = Brain.generate(Settings(app), "Answer with one word.", listOf("user" to "What is the main background colour of this picture?"),
                json = false, image = img.jpeg).trim()
            if (!t.contains("red", true)) throw IllegalStateException("unexpected answer: ${t.take(100)}")
            "model saw the test picture: $t"
        }
    }
    DiagRow("PC remote status (up to 25 s)", saveFirst) {
        val r = PcRemote.command(app, "status", "", "", false)
        if (!r.startsWith("PC replied")) throw IllegalStateException(r)
        r.take(400)
    }
    DiagRow("Wrap-up now", saveFirst) {
        Wrap.runNow(app)
        "started: the summary appears as a notification (or a call if \"Ring instead\" is on) in a few seconds"
    }
}
