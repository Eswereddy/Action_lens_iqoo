package com.actionlens

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Parsed(val title: String, val due: LocalDateTime?, val hasTime: Boolean, val cat: String = "General", val amount: String? = null, val rep: String = "")

/** Rule-based extractor for English, Tenglish, Telugu and Hindi. Swap/augment with an on-device LLM later. */
object Parser {
    private val I = RegexOption.IGNORE_CASE
    private val mon = listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
    private val wd = listOf("mon","tue","wed","thu","fri","sat","sun")
    private val dayAfter = Regex("""\b(day after tomorrow|ellundi|parso)\b|ఎల్లుండి|परसों""", I)
    private val tom = Regex("""\b(tomorrow|tmrw|repu|nale|kal)\b|రేపు|कल""", I)
    private val today = Regex("""\b(today|ivala|ivvala|ee roju|aaj)\b|ఈరోజు|ఈ రోజు|आज""", I)
    private val pm = Regex("""\b(evening|night|afternoon|sayantram|saayantram|shaam|raatri)\b|సాయంత్రం|రాత్రి|शाम|रात""", I)
    private val am = Regex("""\b(morning|udayam|subah)\b|ఉదయం|सुबह""", I)
    private val dMon = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s*(${mon.joinToString("|")})[a-z]*""", I)
    private val dSlash = Regex("""\b(\d{1,2})[/-](\d{1,2})\b(?!:)""")
    private val dWd = Regex("""\b(${wd.joinToString("|")})(?:day|sday|nesday|rsday|urday)?\b""", I)
    private val tAmPm = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", I)
    private val tCol = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val tAt = Regex("""\bat\s+(\d{1,2})\b|\b(\d{1,2})\s*(?:ki|ku|ko|baje)\b|(\d{1,2})\s*(?:కి|बजे)""", I)
    private val amt = Regex("""(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d+)?)""", I)
    private val cats = listOf(
        "Bill" to Regex("""\b(bill|recharge|rent|emi|fee|fees|premium|due)\b|₹|\brs\.?\s*\d""", I),
        "Study" to Regex("""\b(assignment|exam|exams|lab|record|submit|submission|viva|test|class|project|notes)\b""", I),
        "Meeting" to Regex("""\b(meeting|meet|call|interview|standup|sync)\b""", I),
        "Event" to Regex("""\b(event|party|fest|hackathon|function|wedding|trip)\b""", I)
    )
    private val weeklyRe = Regex("""\b(weekly|every week|prathi vaaram|har hafte)\b|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*|ప్రతి వారం|हर हफ्ते""", I)
    private val dailyRe = Regex("""\b(daily|every day|everyday|prathi roju|prati roju|roz)\b|ప్రతి రోజు|हर दिन|रोज़|रोज""", I)
    private val filler = Regex("""(^|\s)(ki|ku|ko|baje|కి)(?=\s|$)""", I)

    fun parse(line: String, now: LocalDateTime = LocalDateTime.now()): Parsed {
        val s = line
        val cuts = mutableListOf<String>()
        val t = now.toLocalDate()
        fun hit(r: Regex) = r.find(s)?.also { cuts += it.value }
        val rep = if (hit(weeklyRe) != null) "weekly" else if (hit(dailyRe) != null) "daily" else ""

        var d: LocalDate? = null
        if (hit(dayAfter) != null) d = t.plusDays(2)
        else if (hit(tom) != null) d = t.plusDays(1)
        else if (hit(today) != null) d = t
        if (d == null) hit(dMon)?.let { m ->
            val x = runCatching { LocalDate.of(t.year, mon.indexOf(m.groupValues[2].lowercase()) + 1, m.groupValues[1].toInt()) }.getOrNull()
            d = if (x != null && x.isBefore(t)) x.plusYears(1) else x
        }
        if (d == null) hit(dSlash)?.let { m ->
            d = runCatching { LocalDate.of(t.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
        if (d == null) hit(dWd)?.let { m ->
            val w = wd.indexOf(m.groupValues[1].lowercase()) + 1
            var add = (w - t.dayOfWeek.value + 7) % 7
            if (add == 0) add = 7
            d = t.plusDays(add.toLong())
        }

        var h: Int? = null
        var mi = 0
        val a = hit(tAmPm)
        if (a != null) {
            h = a.groupValues[1].toInt() % 12 + (if (a.groupValues[3].lowercase() == "pm") 12 else 0)
            mi = a.groupValues[2].ifEmpty { "0" }.toInt()
        } else {
            val c = hit(tCol)
            if (c != null) {
                h = c.groupValues[1].toInt(); mi = c.groupValues[2].toInt()
                if (h in 1..6 && !am.containsMatchIn(s)) h += 12
            } else {
                val x = hit(tAt)
                if (x != null) {
                    h = x.groupValues[1].ifEmpty { x.groupValues[2] }.ifEmpty { x.groupValues[3] }.toInt()
                    if (pm.containsMatchIn(s) && h < 12) h += 12
                    else if (!am.containsMatchIn(s) && h in 1..6) h += 12
                }
            }
        }
        if (rep.isNotEmpty() && h == null) h = 9
        pm.find(s)?.let { cuts += it.value }
        am.find(s)?.let { cuts += it.value }

        var dd = d
        if (dd == null && h != null) {
            dd = t
            if (LocalDateTime.of(dd, LocalTime.of(h.coerceIn(0, 23), mi.coerceIn(0, 59))).isBefore(now)) dd = dd.plusDays(1)
        }
        val due = dd?.let {
            LocalDateTime.of(it, if (h == null) LocalTime.of(23, 59) else LocalTime.of(h.coerceIn(0, 23), mi.coerceIn(0, 59)))
        }
        var title = s
        cuts.forEach { title = title.replace(it, " ") }
        title = title.replace(filler, " ").replace(Regex("""\s+"""), " ").trim(' ', ',', '.', '-', ':')
        if (title.isEmpty()) title = line.trim()
        val cat = cats.firstOrNull { it.second.containsMatchIn(line) }?.first ?: "General"
        val amount = amt.find(line)?.let { "₹" + it.groupValues[1] }
        return Parsed(title, due, h != null, cat, amount, rep)
    }
}
