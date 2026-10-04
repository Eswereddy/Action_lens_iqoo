package com.actionlens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tasks = mutableStateListOf<Task>()
    private val inbox = mutableStateListOf<Sug>()
    private val note = mutableStateOf("")
    private val shared = mutableStateOf<String?>(null)
    private val captured = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tasks.addAll(Store.loadTasks(this))
        captured.value = Store.captured(this)
        Reminders.ensureChannel(this)
        Reminders.scheduleBrief(this)
        tasks.forEach { Reminders.schedule(this, it) }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF062925), primaryContainer = Color(0xFF134E48), onPrimaryContainer = Color(0xFFD7F5F0))
                else lightColorScheme(primary = Color(0xFF0F766E), primaryContainer = Color(0xFFCCEFE9), onPrimaryContainer = Color(0xFF062925))) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() { super.onResume(); inbox.clear(); inbox.addAll(Store.loadInbox(this)) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        if (i.type?.startsWith("text/") == true) i.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { ocr(it) { txt -> shared.value = txt } }
    }

    private fun persist() = Store.saveTasks(this, tasks.toList())
    private fun ms(p: Parsed) = p.due?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    private fun addText(text: String): Int {
        var n = 0
        text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val p = Parser.parse(line)
            val t = Task(System.nanoTime(), p.title, ms(p), p.hasTime, false, p.cat, p.amount, p.rep)
            tasks.add(t); Reminders.schedule(this, t); n++
        }
        persist(); Store.bump(this, n); captured.value = Store.captured(this)
        return n
    }

    private fun replace(old: Task, nt: Task) {
        val i = tasks.indexOfFirst { it.id == old.id }
        if (i < 0) return
        Reminders.cancel(this, old); tasks[i] = nt; Reminders.schedule(this, nt); persist()
    }

    private fun toggleDone(t: Task, c: Boolean) {
        replace(t, t.copy(done = c))
        if (c && t.rep.isNotEmpty() && t.due != null) {
            val step = if (t.rep == "daily") 86_400_000L else 7 * 86_400_000L
            val next = t.copy(id = System.nanoTime(), due = t.due + step, done = false)
            tasks.add(next); Reminders.schedule(this, next); persist()
            note.value = "Next ${t.rep} task created."
        }
    }

    private fun ocr(uri: Uri, done: (String) -> Unit) {
        note.value = "Reading image..."
        val img = try { InputImage.fromFilePath(this, uri) } catch (e: Exception) { note.value = "Could not open image."; return }
        val rec = listOf(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()))
        val lines = linkedSetOf<String>()
        var left = rec.size
        rec.forEach { r ->
            r.process(img).addOnSuccessListener { res -> res.text.split("\n").forEach { l -> if (l.isNotBlank()) lines.add(l.trim()) } }
                .addOnCompleteListener {
                    if (--left == 0) { note.value = if (lines.isEmpty()) "No text found." else "Text read. Review and tap Add."; done(lines.joinToString("\n")) }
                }
        }
    }

    private fun fmt(t: Task): String {
        val d = t.due ?: return "No date"
        return SimpleDateFormat(if (t.hasTime) "EEE d MMM, h:mm a" else "EEE d MMM", Locale.getDefault()).format(Date(d))
    }

    private fun toCalendar(t: Task) {
        val s = t.due ?: System.currentTimeMillis()
        try {
            startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, t.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s + 3_600_000L))
        } catch (e: ActivityNotFoundException) { note.value = "No calendar app found." }
    }

    private fun shareList() {
        val body = tasks.filter { !it.done }.sortedBy { it.due ?: Long.MAX_VALUE }.joinToString("\n") { "- ${it.title} (${fmt(it)})" }
        if (body.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Share task list"))
    }

    @Composable
    private fun Screen() {
        var input by remember { mutableStateOf("") }
        var lang by remember { mutableStateOf("te-IN") }
        var filter by remember { mutableStateOf("All") }
        var editing by remember { mutableStateOf<Task?>(null) }
        var editText by remember { mutableStateOf("") }
        shared.value?.let { input = (input + "\n" + it).trim(); shared.value = null }

        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input + "\n" + it).trim() }
        }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { u -> ocr(u) { txt -> input = (input + "\n" + txt).trim() } }
        }
        val now = System.currentTimeMillis()
        val open = tasks.filter { !it.done }.sortedWith(compareBy<Task> { !it.pin }.thenBy { it.due ?: Long.MAX_VALUE })
        val overdue = open.count { (it.due ?: Long.MAX_VALUE) < now }
        val shown = tasks.filter { filter == "All" || it.cat == filter }
            .sortedWith(compareBy<Task> { it.done }.thenBy { !it.pin }.thenBy { it.due ?: Long.MAX_VALUE })

        editing?.let { et ->
            AlertDialog(onDismissRequest = { editing = null },
                title = { Text("Edit task") },
                text = { OutlinedTextField(editText, { editText = it }, label = { Text("e.g. Submit record repu 5 pm") }) },
                confirmButton = { TextButton({
                    val p = Parser.parse(editText)
                    replace(et, et.copy(title = p.title, due = ms(p) ?: et.due, hasTime = if (p.due != null) p.hasTime else et.hasTime, cat = p.cat, amount = p.amount, rep = p.rep.ifEmpty { et.rep }))
                    editing = null
                }) { Text("Save") } },
                dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
        }

        LazyColumn(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ActionLens", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    TextButton({ shareList() }) { Text("Share list") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Today's focus", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (open.isEmpty()) Text("Nothing pending.") else open.take(3).forEach { Text("- ${it.title} (${fmt(it)})") }
                        if (overdue > 0) Text("$overdue overdue", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text("${captured.value} captured, ${tasks.count { it.done }} done, about ${captured.value * 45 / 60} min saved. Data stays on this phone.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Paste a message, notice, or speak") }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (input.isNotBlank()) { note.value = "${addText(input)} task(s) added."; input = "" } }) { Text("Add") }
                    OutlinedButton({
                        try {
                            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                        } catch (e: ActivityNotFoundException) { note.value = "Voice input not available." }
                    }) { Text("Speak") }
                    OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Scan") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("te-IN" to "Telugu", "hi-IN" to "Hindi", "en-IN" to "English").forEach { (c, n) -> FilterChip(lang == c, { lang = c }, { Text(n) }) }
                }
            }
            item { OutlinedButton({ startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Auto-capture from WhatsApp / SMS") } }
            if (note.value.isNotEmpty()) item { Text(note.value, style = MaterialTheme.typography.bodySmall) }

            if (inbox.isNotEmpty()) {
                item { Text("Found in your messages", fontWeight = FontWeight.Bold) }
                items(inbox.toList(), key = { "s" + it.text }) { s ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.from, style = MaterialTheme.typography.labelMedium)
                            Text(s.text)
                            Row {
                                TextButton({ addText(s.text); inbox.remove(s); Store.saveInbox(this@MainActivity, inbox.toList()) }) { Text("Add task") }
                                TextButton({ inbox.remove(s); Store.saveInbox(this@MainActivity, inbox.toList()) }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Bill", "Study", "Meeting", "Event").forEach { c -> FilterChip(filter == c, { filter = c }, { Text(c) }) }
                }
            }
            items(shown, key = { it.id }) { t ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.done, { c -> toggleDone(t, c) })
                        Column(Modifier.weight(1f)) {
                            Text((if (t.pin) "* " else "") + t.title, fontWeight = FontWeight.SemiBold)
                            Text(fmt(t), style = MaterialTheme.typography.bodySmall,
                                color = if (!t.done && (t.due ?: Long.MAX_VALUE) < now) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t.cat + (t.amount?.let { "  $it" } ?: "") + (if (t.rep.isNotEmpty()) "  repeats ${t.rep}" else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            TextButton({ menu = true }) { Text("More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Edit") }, { menu = false; editText = t.title; editing = t })
                                DropdownMenuItem({ Text(if (t.pin) "Unpin" else "Pin to top") }, { menu = false; replace(t, t.copy(pin = !t.pin)) })
                                DropdownMenuItem({ Text("Snooze 1 hour") }, { menu = false; replace(t, t.copy(due = (t.due ?: System.currentTimeMillis()) + 3_600_000L, hasTime = true)) })
                                DropdownMenuItem({ Text("Snooze to tomorrow") }, { menu = false; replace(t, t.copy(due = (t.due ?: System.currentTimeMillis()) + 86_400_000L)) })
                                DropdownMenuItem({ Text("Add to calendar") }, { menu = false; toCalendar(t) })
                                DropdownMenuItem({ Text("Delete") }, { menu = false; Reminders.cancel(this@MainActivity, t); tasks.removeAll { it.id == t.id }; persist() })
                            }
                        }
                    }
                }
            }
        }
    }
}
