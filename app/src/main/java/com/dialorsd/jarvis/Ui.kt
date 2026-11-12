package com.dialorsd.jarvis

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

val Cyan = Color(0xFF22D3EE)
val Bg = Color(0xFF05090D)
val Panel = Color(0xFF0C141B)
val Dim = Color(0xFF6B8794)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, onPrimary = Color.Black, secondary = Cyan,
            background = Bg, surface = Panel, onBackground = Color(0xFFDCEBF0), onSurface = Color(0xFFDCEBF0),
            surfaceVariant = Panel, onSurfaceVariant = Dim, outline = Color(0xFF1E3540),
        ),
        content = content,
    )
}

/** Original animated orb: concentric rings whose motion depends on the assistant state. */
@Composable
fun Orb(state: OrbState, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "p")
    Canvas(modifier) {
        if (size.minDimension < 8f) return@Canvas
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 * 0.9f
        val core = when (state) {
            OrbState.IDLE -> 0.30f + 0.02f * pulse
            OrbState.LISTENING -> 0.34f + 0.06f * pulse
            OrbState.THINKING -> 0.30f
            OrbState.SPEAKING -> 0.32f + 0.05f * sin(t * 2 * PI.toFloat() * 8).let { it * it }
        }
        // glow
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f), Color.Transparent), c, r), r, c)
        // core
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Cyan, Cyan.copy(alpha = 0.2f)), c, r * core), r * core, c)
        // outer ring
        drawCircle(Cyan.copy(alpha = 0.5f), r * 0.82f, c, style = Stroke(2f))
        // tick ring (slow rotation)
        val ticks = 60
        for (i in 0 until ticks) {
            val a = (i.toFloat() / ticks + t * 0.25f) * 2 * PI.toFloat()
            val long = i % 5 == 0
            val r1 = r * 0.86f
            val r2 = r * (if (long) 0.93f else 0.89f)
            drawLine(Cyan.copy(alpha = if (long) 0.8f else 0.35f),
                Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), 2f)
        }
        when (state) {
            OrbState.THINKING -> {
                val sweep = 70f
                for (k in 0 until 3) {
                    val rr = r * (0.48f + k * 0.1f)
                    val start = (t * 360f * (if (k % 2 == 0) 2 else -3)) + k * 120f
                    drawArc(Cyan.copy(alpha = 0.9f - k * 0.2f), start, sweep, false,
                        Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(4f, cap = StrokeCap.Round))
                }
            }
            OrbState.LISTENING -> {
                for (k in 0 until 3) {
                    val p = ((t * 3 + k / 3f) % 1f)
                    drawCircle(Cyan.copy(alpha = (1f - p) * 0.7f), r * (0.35f + p * 0.45f), c, style = Stroke(3f))
                }
            }
            OrbState.SPEAKING -> {
                val bars = 48
                for (i in 0 until bars) {
                    val a = i.toFloat() / bars * 2 * PI.toFloat()
                    val amp = 0.5f + 0.5f * sin(a * 3 + t * 2 * PI.toFloat() * 6) * sin(a * 5 - t * 2 * PI.toFloat() * 4)
                    val r1 = r * 0.42f
                    val r2 = r1 + r * 0.25f * amp
                    drawLine(Cyan.copy(alpha = 0.85f), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                        Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), 4f, cap = StrokeCap.Round)
                }
            }
            OrbState.IDLE -> {
                drawCircle(Cyan.copy(alpha = 0.25f + 0.2f * pulse), r * 0.55f, c, style = Stroke(1.5f))
            }
        }
    }
}

/** Simple microphone glyph so we don't need an icon library. */
@Composable
fun MicGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bw = w * 0.3f; val bh = h * 0.5f
        drawRoundRect(color, Offset((w - bw) / 2, h * 0.08f), Size(bw, bh),
            androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2))
        val aw = w * 0.56f
        drawArc(color, 0f, 180f, false, Offset((w - aw) / 2, h * 0.22f), Size(aw, h * 0.5f), style = Stroke(w * 0.07f))
        drawLine(color, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.88f), w * 0.07f)
        drawLine(color, Offset(w * 0.34f, h * 0.9f), Offset(w * 0.66f, h * 0.9f), w * 0.07f, cap = StrokeCap.Round)
    }
}
