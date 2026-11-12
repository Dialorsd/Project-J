package com.dialorsd.jarvis

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * v4 PC remote: the phone asks the PC helper (pc/jarvis-listen.ps1 / .sh) to run a NAMED, allowlisted command.
 *
 * Security design (see pc/README.md):
 *  - The phone never sends shell text, only a command name (+ optional process / command name checked
 *    against [A-Za-z0-9._ -]). The PC only runs names enabled in its own ~/.jarvis-pc.json.
 *  - Every message is "JARVIS1 <ts> <nonce> <payload-base64url> <mac-hex>" where
 *    mac = HMAC-SHA256(secret, "<dir>\n<ts>\n<nonce>\n<payload-base64url>") and dir is "cmd" (phone -> PC) or
 *    "reply" (PC -> phone), so a captured command can never be replayed as a reply or the other way round.
 *  - The PC rejects bad MACs, timestamps more than 60 s away and nonces it has seen. Knowing the ntfy topics
 *    is not enough to make the PC do anything.
 *  - Commands go to a separate random topic; replies come back on the existing PC link (ping) topic.
 *  - shutdown / restart / sleep need a spoken "yes" in a later turn (enforced here, not only by the prompt).
 */
object PcRemote {
    const val PREFIX = "JARVIS1 "
    private val NAME_OK = Regex("^[A-Za-z0-9._ -]{1,64}$")
    val COMMANDS = listOf("status", "lock", "sleep", "shutdown", "restart", "cancel_shutdown", "shutdown_when_done", "watch_exit", "run")
    val DANGEROUS = setOf("sleep", "shutdown", "restart", "shutdown_when_done")

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var pendingConfirm: Pair<String, Long>? = null

    fun paired(s: Settings) = s.pcSecret.isNotBlank() && s.pcCmdTopic.isNotBlank()

    /** Creates a new secret and command topic. Returns the pairing code to paste into the PC helper. */
    fun pair(s: Settings): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        s.pcSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        s.pcCmdTopic = newTopic().replace("jarvis-", "jarvis-cmd-")
        return pairingCode(s)
    }

    fun pairingCode(s: Settings): String = "J1,${s.ntfyServer},${s.ntfyTopic},${s.pcCmdTopic},${s.pcSecret}"

    fun unpair(s: Settings) { s.pcSecret = ""; s.pcCmdTopic = "" }

    fun mac(secret: String, dir: String, ts: Long, nonce: String, payloadB64: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return m.doFinal("$dir\n$ts\n$nonce\n$payloadB64".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun newNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Action/tool entry. Blocking-free (suspends). Returns text for the model: the PC's reply, or why it was not sent.
     * [confirmed] must be true for dangerous commands, and only counts after the user spoke since Jarvis asked.
     */
    suspend fun command(ctx: Context, cmdIn: String, name: String, arg: String, confirmed: Boolean): String {
        val s = Settings(ctx)
        val cmd = cmdIn.trim().lowercase()
        if (cmd !in COMMANDS) return "NOT SENT: unknown PC command \"$cmdIn\". Known: ${COMMANDS.joinToString()}."
        if (!paired(s)) return "NOT SENT: the PC is not paired. Tell the user: Jarvis Settings -> PC link -> PAIR PC, then run the pairing step on the PC (see pc/README.md)."
        if (!s.pcLink) return "NOT SENT: PC link is off, so the reply could not arrive. Tell the user to turn on Settings -> PC link."
        val n = name.trim(); val a = arg.trim()
        if (n.isNotEmpty() && !NAME_OK.matches(n)) return "NOT SENT: the name may only contain letters, digits, space, dot, dash and underscore."
        if (a.isNotEmpty() && !NAME_OK.matches(a)) return "NOT SENT: the argument may only contain letters, digits, space, dot, dash and underscore."
        if (cmd == "run" && n.isEmpty()) return "NOT SENT: say which named command to run (name)."
        if (cmd in setOf("watch_exit", "shutdown_when_done") && a.isEmpty() && n.isEmpty()) return "NOT SENT: say which program (process name) to watch."

        if (cmd in DANGEROUS) {
            val p = pendingConfirm
            val now = System.currentTimeMillis()
            val spokeSince = p != null && Store.messages.toList().any { it.role == "user" && it.time > p.second + 500 }
            if (!confirmed || p == null || p.first != cmd || now - p.second > 3 * 60_000L || !spokeSince) {
                pendingConfirm = cmd to now
                return "NOT SENT YET: \"$cmd\" needs the user's explicit confirmation. Ask him now (e.g. \"Shall I, sir?\"). " +
                    "Only if he clearly says yes in his NEXT message, call pc_command again with the same cmd and confirmed=true."
            }
            pendingConfirm = null
        }

        val payload = JSONObject().put("cmd", cmd)
        if (n.isNotEmpty()) payload.put("name", n)
        if (a.isNotEmpty()) payload.put("arg", a)
        val nonce = newNonce()
        val waiter = CompletableDeferred<String>()
        waiters[nonce] = waiter
        try {
            withContext(Dispatchers.IO) { publish(s, s.pcCmdTopic, envelope(s.pcSecret, "cmd", nonce, payload.toString()), cache = false) }
        } catch (e: Exception) {
            waiters.remove(nonce)
            return "NOT SENT: could not reach the ntfy server (${e.message})."
        }
        EventLog.add(ctx, "pc", "Sent PC command: $cmd ${n.ifEmpty { a }}".trim())
        val reply = withTimeoutOrNull(25_000) { waiter.await() }
        waiters.remove(nonce)
        return reply?.let { "PC replied: $it" }
            ?: "Sent \"$cmd\", but no reply from the PC within 25 seconds. The PC may be off or asleep, or jarvis-listen is not running there."
    }

    fun envelope(secret: String, dir: String, nonce: String, payloadJson: String): String {
        val ts = System.currentTimeMillis() / 1000
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$PREFIX$ts $nonce $b64 ${mac(secret, dir, ts, nonce, b64)}"
    }

    private fun publish(s: Settings, topic: String, body: String, cache: Boolean) {
        val c = URL("${s.ntfyServer}/${URLEncoder.encode(topic, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (!cache) c.setRequestProperty("Cache", "no") // commands are useless after 60 s anyway
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Called by the PC link for messages starting with [PREFIX]. Verifies the MAC (dir "reply"), the age
     * (6 hours: replies may wait on ntfy while the phone is offline) and replays. Returns true if handled.
     */
    fun onIncoming(ctx: Context, message: String): Boolean {
        val s = Settings(ctx)
        val parts = message.trim().split(' ')
        if (parts.size != 5 || "${parts[0]} " != PREFIX) return true
        val (_, tsS, nonce, b64, sig) = parts
        val ts = tsS.toLongOrNull() ?: return true
        if (s.pcSecret.isBlank()) { Store.addMessage("note", "Ignored a PC remote reply: the PC is not paired."); return true }
        if (!constEq(mac(s.pcSecret, "reply", ts, nonce, b64), sig.lowercase())) {
            Store.addMessage("note", "Ignored a PC remote message with a wrong signature (re-pair the PC if this keeps happening).")
            return true
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 6 * 3600) return true
        val seen = s.pcReplySeen.split(',').filter { it.isNotBlank() }
        if (nonce in seen) return true
        s.pcReplySeen = (seen + nonce).takeLast(100).joinToString(",")
        val payload = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)) }.getOrNull() ?: return true
        val text = payload.optString("text").take(3000)
        val ok = payload.optBoolean("ok", true)
        val re = payload.optString("re")
        val event = payload.optString("event")
        val line = (if (ok) "" else "FAILED: ") + text
        EventLog.add(ctx, "pc", "PC: " + line.take(200))
        Store.addMessage("note", "PC remote: $line")
        // A request is waiting for this answer: it goes to the model as the tool/action result.
        if (re.isNotBlank()) waiters[re]?.let { if (it.complete(line)) return true }
        // Late reply or an event (e.g. "the program you watched has exited").
        if (event.isNotBlank()) {
            val call = CallInfo(Store.nextId(), "pc", "PC", "", line)
            Store.putCall(call)
            Caller.ring(ctx, call)
        } else {
            say(ctx, "Your PC replied: $line")
        }
        return true
    }

    private fun say(ctx: Context, text: String) {
        when {
            LiveSession.active && !LiveSession.interpreter -> LiveSession.announce(text)
            Store.appVisible -> Jarvis.scope.launch { Voice.speak(ctx.applicationContext, text) }
            else -> {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                val n = NotificationCompat.Builder(ctx, PcLinkService.CH_PC_INFO).setSmallIcon(R.drawable.ic_stat_jarvis)
                    .setContentTitle("PC").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true).build()
                runCatching { nm.notify(20_000 + Store.nextId(), n) }
            }
        }
    }
}
