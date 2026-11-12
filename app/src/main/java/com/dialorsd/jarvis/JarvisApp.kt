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
        Memories.load(this)
        Caller.createChannels(this)
        if (Settings(this).pcLink) PcLinkService.start(this)
    }
}

/** Conversation flow: user text -> brain -> actions -> speech. Survives the activity being closed. */
object Jarvis {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set by MainActivity: asks for the calendar permission when an action needs it. */
    var requestCalendar: (() -> Unit)? = null

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
            val uk = hasCyrillic(clean)
            val reply = try {
                withContext(Dispatchers.IO) {
                    val c = ChatContext(Store.loadTimers(), runCatching { DeviceCalendar.upcoming(ctx) }.getOrNull(), s.webSearch)
                    Brain.chat(s, history, clean, c)
                }
            } catch (e: Exception) {
                Reply(Brain.errorLine(e, uk), emptyList())
            }
            val (notes, failed) = runActions(ctx, reply.actions, uk)
            var say = reply.say.ifBlank { notes.ifBlank { if (uk) "Так, сер." else "Very good, sir." } }
            if (failed.isNotBlank()) say += " " + failed
            Store.addMessage("jarvis", say)
            Store.addSources(reply.sources)
            if (notes.isNotBlank() && reply.say.isNotBlank()) Store.addMessage("note", notes)
            Store.setOrb(OrbState.IDLE)
            Voice.speak(ctx, say)
        }
    }

    /** Executes actions; returns (note of what was done, spoken sentence about failures). */
    private suspend fun runActions(ctx: Context, actions: List<Action>, uk: Boolean): Pair<String, String> {
        val notes = mutableListOf<String>()
        val fails = mutableListOf<String>()
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
                        .onFailure { fails += if (uk) "На жаль, не вдалося запустити завдання." else "I'm afraid the task could not be started." }
                }
                "remember" -> a.str("text").takeIf { it.isNotBlank() }?.let {
                    Memories.remember(ctx, it)
                    notes += if (uk) "Запам'ятав: $it" else "Remembered: $it"
                }
                "forget" -> {
                    val n = Memories.forget(ctx, a.str("match"))
                    notes += if (uk) "Забуто записів: $n" else "Forgot $n memory item(s)"
                }
                "calendar_add" -> {
                    if (!DeviceCalendar.canWrite(ctx)) {
                        fails += if (uk) "Але я не маю доступу до календаря, сер. Дозвольте його, і повторіть." else "However, I have no access to your calendar, sir. Please allow it and ask again."
                        requestCalendar?.invoke()
                    } else {
                        try {
                            val r = withContext(Dispatchers.IO) { DeviceCalendar.add(ctx, a.str("title"), a.str("start"), a.str("end"), a.str("location")) }
                            notes += if (uk) "Додано в календар: $r" else "Added to calendar: $r"
                        } catch (e: Exception) {
                            fails += if (uk) "На жаль, подію не вдалося додати: ${e.message}." else "I'm afraid the event could not be added: ${e.message}."
                        }
                    }
                }
                "navigate" -> a.str("destination").takeIf { it.isNotBlank() }?.let {
                    Navigator.navigate(ctx, it)
                    notes += if (uk) "Навігація: $it" else "Navigating to $it"
                }
            }
        }
        return notes.joinToString(". ") to fails.joinToString(" ")
    }
}
