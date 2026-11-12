package com.dialorsd.jarvis

/**
 * v6: "report back, then wait for my next task". After the user answers a call and Jarvis has spoken his line, the
 * call screen can carry on straight into a voice session. This holds what the call was about, so the session (Gemini
 * Live or the Groq voice loop) knows the full task result and can handle "make it shorter" or "now do the same for Y".
 */
object PostCall {
    class Seed(val kind: String, val label: String, val said: String, val result: String)

    @Volatile var seed: Seed? = null

    fun clear() { seed = null }

    /** Text added to the model's context while a session that continues a call is running; empty otherwise. */
    fun block(): String {
        val sd = seed ?: return ""
        val what = when (sd.kind) {
            "task" -> "a background task he gave you (\"${sd.label}\") has finished"
            "timer" -> "his timer or reminder \"${sd.label}\" went off"
            "pc" -> "his computer sent a message (\"${sd.label}\")"
            "watch" -> "a web watcher he set up triggered (\"${sd.label}\")"
            "morning" -> "you gave him the morning briefing"
            "wrap" -> "you gave him the evening wrap-up"
            else -> "you phoned him"
        }
        val result = if (sd.kind == "task" && sd.result.isNotBlank())
            "\nThe full result of that task was:\n${sd.result.take(6000)}\n" else if (sd.result.isNotBlank()) "\nDetails: ${sd.result.take(1500)}\n" else ""
        return "You have just phoned the user because $what. You told him: \"${sd.said.take(600)}\"$result" +
            "He picked up and is now on the line, about to reply or give his next instruction. Do not repeat the report unless he asks. " +
            "Use the result above when he asks for changes (\"make it shorter\"), follow-ups or \"now do the same for ...\"; " +
            "long jobs go to a new background task. If he hands you a task and says he will give another afterwards or asks " +
            "you to wait for his answer, set then_wait on it. Stay quiet until he speaks."
    }

    /** Whether to carry on listening after this call was answered. [sessionWasActive]: he was already in a conversation. */
    fun shouldKeep(s: Settings, call: CallInfo, sessionWasActive: Boolean): Boolean {
        if (call.keep == 1) return true       // the task was given with then_wait: always, whatever the setting says
        if (sessionWasActive) return true     // a session was running when the call came in: do not drop it
        return when (s.afterCall) {
            AfterCall.ALWAYS -> true
            AfterCall.TASKS -> call.kind == "task"
            AfterCall.NEVER -> false
        }
    }

    /** The closing question of a report on a task. */
    fun nextPrompt(uk: Boolean) = if (uk) "Беремося до наступного, сер?" else "Shall I take the next one, sir?"
}
