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
        Notes.load(this)
        Caller.createChannels(this)
        val s = Settings(this)
        if (s.pcLink) PcLinkService.start(this)
        Proactive.registerBatteryReceiver(this)
    }
}

/** Builds the chat context from device state. Blocking (calendar query): call off the main thread. */
fun chatContext(ctx: Context, s: Settings): ChatContext =
    ChatContext(Store.loadTimers(), runCatching { DeviceCalendar.upcoming(ctx) }.getOrNull(), s.webSearch)

/** Conversation flow: user text -> brain -> actions -> speech. Survives the activity being closed. */
object Jarvis {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set by MainActivity: asks for the calendar permission when an action needs it. */
    var requestCalendar: (() -> Unit)? = null

    fun send(context: Context, text: String) {
        val ctx = context.applicationContext
        val clean = text.trim()
        if (clean.isEmpty()) return
        // In Live mode, typed text goes into the running conversation.
        if (LiveSession.active && LiveSession.sendText(clean)) {
            Store.addMessage("user", clean)
            return
        }
        Voice.stop()
        val history = Store.messages.toList()
        Store.addMessage("user", clean)
        Store.setOrb(OrbState.THINKING)
        scope.launch {
            val s = Settings(ctx)
            val uk = hasCyrillic(clean)
            val reply = try {
                withContext(Dispatchers.IO) {
                    Brain.chat(s, history, clean, chatContext(ctx, s))
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
            withContext(Dispatchers.IO) { runCatching { Proactive.scheduleEvents(ctx) } }
            Voice.speak(ctx, say)
        }
    }

    /** Executes actions; returns (note of what was done, spoken sentence about failures). */
    suspend fun runActions(ctx: Context, actions: List<Action>, uk: Boolean): Pair<String, String> {
        val notes = mutableListOf<String>()
        val fails = mutableListOf<String>()
        for (a in actions) {
            when (a.type) {
                "timer" -> if (a.seconds > 0) {
                    val t = withContext(Dispatchers.IO) { Timers.add(ctx, a.seconds, a.label) }
                    notes += if (uk) "Таймер «${t.label}» на ${Timers.formatDuration(a.seconds)}" else "Timer \"${t.label}\" set for ${Timers.formatDuration(a.seconds)}"
                    if (!Timers.canExact(ctx)) notes += if (uk) "(точні будильники вимкнені, можлива затримка)" else "(exact alarms are off, it may ring late)"
                }
                "reminder" -> {
                    val at = DeviceCalendar.parseLocal(a.str("at"))
                    val text = a.str("text").ifBlank { a.label }.ifBlank { if (uk) "нагадування" else "reminder" }
                    val ms = at?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                    if (ms == null || ms <= System.currentTimeMillis()) {
                        fails += if (uk) "Але я не зрозумів час нагадування, сер." else "However, I could not make out a future time for that reminder, sir."
                    } else {
                        val t = withContext(Dispatchers.IO) { Timers.addAt(ctx, ms, text) }
                        val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.endAt))
                        notes += if (uk) "Нагадування «${t.label}» на $f" else "Reminder \"${t.label}\" set for $f"
                    }
                }
                "note_add" -> a.str("text").takeIf { it.isNotBlank() }?.let {
                    Notes.addNote(ctx, it)
                    notes += if (uk) "Нотатку збережено" else "Note saved"
                }
                "list_add" -> a.str("item").takeIf { it.isNotBlank() }?.let {
                    val l = Notes.normList(a.str("list"))
                    Notes.addItem(ctx, l, it)
                    notes += if (uk) "Додано до списку «$l»: $it" else "Added to $l list: $it"
                }
                "list_remove" -> {
                    val l = Notes.normList(a.str("list"))
                    val n = Notes.removeItem(ctx, l, a.str("item"))
                    notes += if (uk) "Видалено зі списку «$l»: $n" else "Removed $n item(s) from $l list"
                }
                "list_clear" -> {
                    val l = Notes.normList(a.str("list"))
                    val n = Notes.clearList(ctx, l)
                    notes += if (uk) "Список «$l» очищено ($n)" else "Cleared $l list ($n item(s))"
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
