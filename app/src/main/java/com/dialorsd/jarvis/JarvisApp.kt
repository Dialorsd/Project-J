package com.dialorsd.jarvis

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Caller.createChannels(this)
    }
}

/** Conversation flow: user text -> brain -> actions -> speech. Survives the activity being closed. */
object Jarvis {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun send(context: Context, text: String) {
        val ctx = context.applicationContext
        val clean = text.trim()
        if (clean.isEmpty()) return
        Voice.stop()
        val history = Store.messages.toList()
        Store.addMessage("user", clean)
        Store.setOrb(OrbState.THINKING)
        scope.launch {
            val s = Settings(ctx)
            val reply = try {
                withContext(Dispatchers.IO) { Brain.chat(s, history, clean, Store.loadTimers()) }
            } catch (e: Exception) {
                Reply(Brain.errorLine(e, hasCyrillic(clean)), emptyList())
            }
            val notes = runActions(ctx, reply.actions, hasCyrillic(clean))
            val say = reply.say.ifBlank { notes.ifBlank { if (hasCyrillic(clean)) "Так, сер." else "Very good, sir." } }
            Store.addMessage("jarvis", say)
            if (notes.isNotBlank() && reply.say.isNotBlank()) Store.addMessage("note", notes)
            Store.setOrb(OrbState.IDLE)
            Voice.speak(ctx, say)
        }
    }

    /** Executes actions; returns a short note of what was done (for the transcript). */
    private suspend fun runActions(ctx: Context, actions: List<Action>, uk: Boolean): String {
        val notes = mutableListOf<String>()
        for (a in actions) {
            when (a.type) {
                "timer" -> if (a.seconds > 0) {
                    val t = withContext(Dispatchers.IO) { Timers.add(ctx, a.seconds, a.label) }
                    notes += if (uk) "Таймер «${t.label}» на ${Timers.formatDuration(a.seconds)}" else "Timer \"${t.label}\" set for ${Timers.formatDuration(a.seconds)}"
                    if (!Timers.canExact(ctx)) notes += if (uk) "(точні будильники вимкнені, можлива затримка)" else "(exact alarms are off, it may ring late)"
                }
                "cancel_timer" -> {
                    val n = withContext(Dispatchers.IO) { Timers.cancelByLabel(ctx, a.label) }
                    notes += if (uk) "Скасовано таймерів: $n" else "Cancelled $n timer(s)"
                }
                "task" -> if (a.prompt.isNotBlank()) {
                    runCatching { TaskService.start(ctx, a.prompt, a.label) }
                        .onSuccess { notes += if (uk) "Завдання «${a.label}» запущено" else "Task \"${a.label}\" started" }
                        .onFailure { notes += if (uk) "Не вдалося запустити завдання" else "Could not start the task" }
                }
            }
        }
        return notes.joinToString(". ")
    }
}
