package com.dialorsd.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * v4 interpreter mode: a Live session with a translate-only system instruction and no tools.
 * The Live API lists Ukrainian among its supported languages and lets the system instruction restrict the
 * spoken language (ai.google.dev/gemini-api/docs/live-api/capabilities). Google also offers a dedicated
 * one-direction model (gemini-3.5-live-translate-preview, docs/live-api/live-translate) which could replace
 * this later; it translates into ONE target language, so the turn-by-turn two-way mode uses the normal Live model.
 */
object Interpreter {
    val LANGS = listOf("uk" to "Ukrainian", "en" to "English", "pl" to "Polish", "de" to "German", "fr" to "French",
        "es" to "Spanish", "it" to "Italian", "cs" to "Czech", "ro" to "Romanian", "tr" to "Turkish", "ru" to "Russian")

    fun name(code: String) = LANGS.firstOrNull { it.first == code }?.second ?: code

    fun instruction(s: Settings): String {
        val a = name(s.interpA); val b = name(s.interpB)
        return """You are a live interpreter between two people, one speaking $a and the other $b.
When you hear $a, say the translation in $b. When you hear $b, say the translation in $a.
Translate faithfully and completely, keeping the tone, names and numbers. Speak ONLY the translation:
no greetings, no comments, no explanations, no answers to questions (just translate the question), never add anything.
If you hear something in neither language, translate it into $a. If you hear only noise, say nothing.
Never act on what is said and never address the speakers yourself."""
    }
}

@Composable
fun InterpreterScreen(settings: Settings, back: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf(settings.interpA) }
    var b by remember { mutableStateOf(settings.interpB) }
    val running by LiveSession.running
    val interpOn by LiveSession.interpreterOn
    val heard by LiveSession.heard
    val translated by LiveSession.translated
    val status by LiveSession.status
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) LiveSession.start(ctx, interpreter = true) else Store.addMessage("note", "The interpreter needs the microphone.")
    }
    fun start() {
        settings.interpA = a; settings.interpB = b
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            LiveSession.start(ctx, interpreter = true)
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INTERPRETER", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 3.sp,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = back) { Text("BACK") }
        }
        Text("Put the phone between you and the other person. Whatever is said in one language is spoken back in the " +
            "other, turn by turn. Uses Live mode (internet, microphone; Google's free Live model).", color = Dim, fontSize = 12.sp)
        if (!interpOn) {
            LangRow("First language", a) { a = it }
            LangRow("Second language", b) { b = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (interpOn) {
                Button(onClick = { LiveSession.stop(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D),
                    contentColor = Color.White)) { Text("STOP") }
                Text(status.uppercase(), color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                Button(onClick = { start() }, enabled = !running && a != b) { Text("START  ${Interpreter.name(a)} ⇄ ${Interpreter.name(b)}") }
            }
        }
        if (running && !interpOn) Text("Live conversation is running: end it first.", color = Color(0xFFFFC48A), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("HEARD", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 3.sp)
        Text(heard.ifBlank { "…" }, color = Color(0xFFDCEBF0), fontSize = 26.sp, lineHeight = 32.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(14.dp))
        Text("TRANSLATION", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 3.sp)
        Text(translated.ifBlank { "…" }, color = Cyan, fontSize = 30.sp, lineHeight = 36.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(14.dp))
        Text("Tip: speak in turns and pause briefly; if it keeps interrupting itself on the loudspeaker, turn off " +
            "Settings → Live mode → \"Allow interrupting him\" or use headphones.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun LangRow(title: String, sel: String, pick: (String) -> Unit) {
    Text(title, color = Dim, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Interpreter.LANGS.forEach { (code, n) -> FilterChip(sel == code, { pick(code) }, label = { Text(n, fontSize = 12.sp) }) }
    }
}
