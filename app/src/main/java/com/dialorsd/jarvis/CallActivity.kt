package com.dialorsd.jarvis

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Incoming-call screen, shown over the lock screen. */
class CallActivity : ComponentActivity() {
    private enum class Phase { RINGING, CONNECTED, ENDED }

    private val phase = mutableStateOf(Phase.RINGING)
    private val caption = mutableStateOf("")
    private var callId = -1
    private var label = ""
    private val kind = mutableStateOf("timer")
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { JarvisTheme { CallScreen() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val id = i?.getIntExtra(Caller.EXTRA_CALL, -1) ?: -1
        val call = Store.getCall(id)
        if (call == null) {
            // Already answered/declined elsewhere.
            if (callId < 0) finish()
            return
        }
        if (phase.value == Phase.CONNECTED && id == callId) return
        callId = id
        label = call.label
        kind.value = call.kind
        phase.value = Phase.RINGING
        caption.value = Caller.describe(call)
        if (i?.action == Caller.ACTION_ANSWER) answer()
    }

    private fun answer() {
        val call = Store.getCall(callId) ?: run { finish(); return }
        Caller.stopRinging(this, call.id)
        Store.removeCall(call.id)
        phase.value = Phase.CONNECTED
        val uk = when (call.kind) {
            "task" -> hasCyrillic(call.line)
            "pc" -> hasCyrillic(call.detail + call.label)
            "timer" -> hasCyrillic(call.label) || Store.lastLanguageIsUkrainian()
            else -> Store.lastLanguageIsUkrainian()
        }
        job = Jarvis.scope.launch {
            val line = if (call.kind == "morning") {
                Store.setOrb(OrbState.THINKING)
                caption.value = if (uk) "Готую ранковий огляд…" else "Preparing your briefing…"
                val text = withContext(Dispatchers.IO) { Morning.briefingText(applicationContext, uk) }
                Store.setOrb(OrbState.IDLE)
                text
            } else if (call.kind == "test") {
                if (uk) "Тестовий дзвінок, сер. Якщо ви мене чуєте, дзвінки працюють." else "A test call, sir. If you can hear me, calls are working."
            } else call.line.ifBlank {
                Store.setOrb(OrbState.THINKING)
                val situation = if (call.kind == "pc")
                    "the user's computer sent a message. Title: \"${call.label}\". Message: \"${call.detail}\". Relay it faithfully."
                else if (call.attempt > 0) "the user has not yet acknowledged the reminder/timer \"${call.label}\"; this is reminder call number ${call.attempt + 1}. Remind him firmly but politely."
                else "the timer or reminder \"${call.label}\" the user asked for is due now."
                val generated = withTimeoutOrNull(8000) {
                    withContext(Dispatchers.IO) { runCatching { Brain.callLine(Settings(this@CallActivity), situation, uk) }.getOrNull() }
                }
                Store.setOrb(OrbState.IDLE)
                generated?.takeIf { it.isNotBlank() }
                    ?: if (call.kind == "pc") Brain.fallbackPcLine(call.label, call.detail, uk) else Brain.fallbackCallLine(call.label, uk)
            }
            caption.value = line
            Store.addMessage("jarvis", line)
            Voice.speak(applicationContext, line)
            delay(1500)
            phase.value = Phase.ENDED
            finish()
        }
    }

    private fun decline() {
        if (callId >= 0) {
            val id = callId
            val app = applicationContext
            Caller.stopRinging(this, id)
            Thread { declineCall(app, id) }.start()
        }
        finish()
    }

    /** Reminder acknowledged without picking up: stops the nagging. */
    private fun done() {
        if (callId >= 0) Caller.markDone(this, callId)
        finish()
    }

    private fun hangUp() {
        job?.cancel()
        Voice.stop()
        Store.setOrb(OrbState.IDLE)
        finish()
    }

    @Composable
    private fun CallScreen() {
        val p by phase
        val text by caption
        val orb by Store.orb
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))
                Text("JARVIS", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 30.sp, letterSpacing = 8.sp)
                Text(if (p == Phase.RINGING) "incoming call" else "connected", color = Dim,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(30.dp))
                Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    if (p == Phase.RINGING) RingPulse(Modifier.fillMaxSize()) else Orb(orb, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(20.dp))
                Text(text, color = Color(0xFFDCEBF0), fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (p == Phase.RINGING) {
                        RoundButton("DECLINE", Color(0xFFE5484D)) { decline() }
                        if (kind.value == "timer") RoundButton("DONE", Color(0xFF3A6EA5)) { done() }
                        RoundButton("ANSWER", Color(0xFF2FBF71)) { answer() }
                    } else {
                        RoundButton("END", Color(0xFFE5484D)) { hangUp() }
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    @Composable
    private fun RoundButton(label: String, color: Color, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(color).border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                .clickable(onClick = onClick))
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color(0xFFDCEBF0), fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
        }
    }
}

/** Expanding rings around a solid core: the "ringing" animation. */
@Composable
private fun RingPulse(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "ring")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val r = size.minDimension / 2
        if (r < 4f) return@Canvas
        for (k in 0 until 4) {
            val p = (t + k / 4f) % 1f
            drawCircle(Cyan.copy(alpha = (1f - p) * 0.8f), r * (0.25f + 0.75f * p), center, style = Stroke(3f + 3f * (1f - p)))
        }
        drawCircle(Cyan, r * 0.22f, center)
        drawCircle(Color.White.copy(alpha = 0.7f), r * 0.1f, center)
    }
}
