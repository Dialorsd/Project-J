package com.dialorsd.jarvis

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Memories.load(this)
        Notes.load(this)
        Notifs.load(this)
        Watchers.load(this)
        Caller.createChannels(this)
        val s = Settings(this)
        if (s.pcLink) PcLinkService.start(this)
        Proactive.registerBatteryReceiver(this)
    }
}

/** Builds the chat context from device state. Blocking (calendar query): call off the main thread. */
fun chatContext(ctx: Context, s: Settings): ChatContext =
    ChatContext(Store.loadTimers(), runCatching { DeviceCalendar.upcoming(ctx) }.getOrNull(), s.webSearch, PostCall.block())

/** Conversation flow: user text -> brain -> actions -> speech. Survives the activity being closed. */
object Jarvis {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set by MainActivity: asks for the calendar permission when an action needs it. */
    var requestCalendar: (() -> Unit)? = null

    fun send(context: Context, text: String) {
        val ctx = context.applicationContext
        // v4: a picture waiting for a question goes with this message (to the text model, also during Live mode).
        val img = Vision.pending.value
        val clean = text.trim().ifEmpty { if (img != null) (if (Store.lastLanguageIsUkrainian()) "Що на цьому зображенні?" else "What is in this picture?") else "" }
        if (clean.isEmpty()) return
        if (img != null) {
            Vision.pending.value = null
            Store.addMessage("image", img.thumbPath)
        }
        // In Live mode, typed text goes into the running conversation.
        if (img == null && LiveSession.active && LiveSession.sendText(clean)) {
            Store.addMessage("user", clean)
            return
        }
        if (!LiveSession.active) Voice.stop()
        val history = Store.messages.toList()
        Store.addMessage("user", clean)
        Store.setOrb(OrbState.THINKING)
        scope.launch {
            val s = Settings(ctx)
            val uk = hasCyrillic(clean)
            val reply = try {
                withContext(Dispatchers.IO) {
                    Brain.chat(s, history, clean, chatContext(ctx, s), img?.jpeg) { a -> runBlocking { Info.run(ctx, a, uk) } }
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
            if (!LiveSession.active) Voice.speak(ctx, say) // during Live mode the answer is only written
            else Store.setOrb(OrbState.LISTENING)
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
                    val wait = a.raw.optBoolean("then_wait", false) || a.raw.optString("then_wait").equals("true", true)
                    runCatching { TaskService.start(ctx, a.prompt, a.label, wait) }
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
                // ---- v4 ----
                "watch_add" -> {
                    val r = withContext(Dispatchers.IO) {
                        Watchers.add(ctx, a.str("kind"), a.str("target"), a.str("condition"),
                            a.raw.optDouble("threshold").takeIf { !it.isNaN() }, a.str("label"),
                            a.raw.optInt("interval_minutes", 0).takeIf { it > 0 }, a.raw.optBoolean("repeat", false))
                    }
                    if (r.startsWith("Watcher \"")) notes += r else fails += r
                }
                "watch_remove" -> {
                    val gone = withContext(Dispatchers.IO) { Watchers.removeByMatch(ctx, a.str("match").ifBlank { a.label }) }
                    notes += if (uk) "Видалено спостерігачів: ${gone.size}" else "Removed ${gone.size} watcher(s)"
                }
            }
        }
        return notes.joinToString(". ") to fails.joinToString(" ")
    }
}

/** v4: info actions (results go back to the model). Shared by text mode (second pass) and Live tools. */
object Info {
    suspend fun run(ctx: Context, a: Action, uk: Boolean): String? = when (a.type) {
        "notifications_read" -> if (!BuildConfig.NOTIF_READER) "Notification reading is not available in this build." else Notifs.read(ctx, a.str("app"), a.raw.optInt("since_minutes", 120).let { if (it <= 0) 120 else it },
            a.raw.optInt("limit", 10).let { if (it <= 0) 10 else it })
        "watch_list" -> withContext(Dispatchers.IO) { Watchers.forPrompt(ctx) }
        "day_summary" -> withContext(Dispatchers.IO) { Wrap.facts(ctx) }
        "pc_command" -> PcRemote.command(ctx, a.str("cmd"), a.str("name"), a.str("arg"), a.raw.optBoolean("confirmed", false))
        else -> null
    }
}
