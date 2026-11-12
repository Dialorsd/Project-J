package com.dialorsd.jarvis

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** One note or list entry. For notes, list == "". */
data class NoteItem(val id: Long, val list: String, val text: String, val done: Boolean = false)

/**
 * Notes and named lists ("shopping", "to do"...), stored on the device (SharedPreferences "jarvis_notes")
 * and given to the brain, capped, so he can answer "what's on my shopping list".
 */
object Notes {
    private const val MAX_ITEMS = 300
    private const val PROMPT_CHARS = 2000
    val items = mutableStateListOf<NoteItem>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis_notes", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(prefs(ctx).getString("items", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(NoteItem(o.optLong("id"), o.optString("l"), o.optString("t"), o.optBoolean("d")))
        }
    }

    @Synchronized
    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("l", it.list).put("t", it.text).put("d", it.done)) }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    private var lastId = 0L
    private fun newId(): Long {
        val now = System.currentTimeMillis()
        lastId = if (now <= lastId) lastId + 1 else now
        return lastId
    }

    /** List names are compared case-insensitively and stored in lower case. */
    fun normList(name: String): String = name.trim().lowercase().ifBlank { "list" }.take(40)

    @Synchronized
    fun addNote(ctx: Context, text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        load(ctx)
        items.add(NoteItem(newId(), "", t))
        trim(); save(ctx)
    }

    @Synchronized
    fun addItem(ctx: Context, list: String, item: String) {
        val t = item.trim().take(200)
        if (t.isEmpty()) return
        load(ctx)
        val l = normList(list)
        val existing = items.indexOfFirst { it.list == l && it.text.equals(t, true) }
        if (existing >= 0) items[existing] = items[existing].copy(done = false)
        else items.add(NoteItem(newId(), l, t))
        trim(); save(ctx)
    }

    /** Removes items of [list] matching [item] (substring, case-insensitive). Returns the number removed. */
    @Synchronized
    fun removeItem(ctx: Context, list: String, item: String): Int {
        load(ctx)
        val l = normList(list)
        val m = item.trim().lowercase()
        if (m.isEmpty()) return 0
        val before = items.size
        items.removeAll { it.list == l && (it.text.lowercase().contains(m) || m.contains(it.text.lowercase())) }
        save(ctx)
        return before - items.size
    }

    @Synchronized
    fun clearList(ctx: Context, list: String): Int {
        load(ctx)
        val l = normList(list)
        val before = items.size
        items.removeAll { it.list == l }
        save(ctx)
        return before - items.size
    }

    @Synchronized
    fun toggle(ctx: Context, id: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items[i] = items[i].copy(done = !items[i].done); save(ctx) }
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        items.removeAll { it.id == id }
        save(ctx)
    }

    private fun trim() { while (items.size > MAX_ITEMS) items.removeAt(0) }

    fun listNames(): List<String> = items.map { it.list }.filter { it.isNotEmpty() }.distinct()

    /** Unfinished notes and list items for the prompt, capped. Empty string if none. */
    @Synchronized
    fun forPrompt(maxChars: Int = PROMPT_CHARS): String {
        val sb = StringBuilder()
        val notes = items.filter { it.list.isEmpty() && !it.done }.reversed()
        if (notes.isNotEmpty()) {
            sb.append("Notes (newest first):\n")
            for (n in notes) { if (sb.length + n.text.length > maxChars) break; sb.append("- ").append(n.text).append('\n') }
        }
        for (l in listNames()) {
            val open = items.filter { it.list == l && !it.done }
            if (open.isEmpty()) continue
            val line = "List \"$l\": " + open.joinToString(", ") { it.text } + "\n"
            if (sb.length + line.length > maxChars) { sb.append("(more lists omitted)\n"); break }
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }
}

// ---------------------------------------------------------------- screen

@Composable
fun NotesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var newNote by remember { mutableStateOf("") }
    var newList by remember { mutableStateOf("") }
    var newItem by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NOTES & LISTS", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 3.sp,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = back) { Text("BACK") }
        }
        Text("Say \"note that…\", \"add milk to my shopping list\", \"remove milk\", \"clear the shopping list\", " +
            "or edit here. Stored only on this phone; open items are sent to Gemini so Jarvis can read them back.",
            color = Dim, fontSize = 12.sp)

        Text("NOTES", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newNote, { newNote = it }, Modifier.weight(1f), label = { Text("New note") }, maxLines = 3)
            TextButton(onClick = { Notes.addNote(ctx, newNote); newNote = "" }, enabled = newNote.isNotBlank()) { Text("ADD") }
        }
        val notes = Notes.items.filter { it.list.isEmpty() }.reversed()
        if (notes.isEmpty()) Text("No notes.", color = Dim, fontSize = 13.sp)
        notes.forEach { ItemRow(it) }

        Spacer(Modifier.height(8.dp))
        Text("LISTS", color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp)
        Notes.listNames().forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.replaceFirstChar { it.uppercase() }, color = Color(0xFFDCEBF0), fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Notes.clearList(ctx, l) }) { Text("CLEAR", color = Dim, fontSize = 12.sp) }
            }
            Notes.items.filter { it.list == l }.forEach { ItemRow(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newList, { newList = it }, Modifier.width(130.dp), label = { Text("List") }, singleLine = true)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), label = { Text("Item") }, singleLine = true)
            TextButton(onClick = { Notes.addItem(ctx, newList.ifBlank { "shopping" }, newItem); newItem = "" },
                enabled = newItem.isNotBlank()) { Text("ADD") }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ItemRow(n: NoteItem) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).clickable { Notes.toggle(ctx, n.id) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(n.done, { Notes.toggle(ctx, n.id) })
        Text(n.text, color = if (n.done) Dim else Color(0xFFDCEBF0), fontSize = 14.sp,
            textDecoration = if (n.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        TextButton(onClick = { Notes.delete(ctx, n.id) }) { Text("DELETE", color = Dim, fontSize = 12.sp) }
    }
}
