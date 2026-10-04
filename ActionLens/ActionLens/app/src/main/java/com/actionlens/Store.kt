package com.actionlens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: Long, val title: String, val due: Long?, val hasTime: Boolean, val done: Boolean = false, val cat: String = "General", val amount: String? = null, val rep: String = "", val pin: Boolean = false)
data class Sug(val from: String, val text: String)

/** All data stays in app-private storage on the phone. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("al", 0)

    fun loadTasks(c: Context): List<Task> {
        val a = JSONArray(p(c).getString("tasks", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Task(o.getLong("id"), o.getString("t"), o.getLong("d").takeIf { v -> v >= 0 }, o.getBoolean("h"), o.getBoolean("x"),
                o.optString("c", "General"), o.optString("a", "").ifEmpty { null }, o.optString("r", ""), o.optBoolean("p", false))
        }
    }

    fun saveTasks(c: Context, l: List<Task>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("d", it.due ?: -1L).put("h", it.hasTime).put("x", it.done).put("c", it.cat).put("a", it.amount ?: "").put("r", it.rep).put("p", it.pin)) }
        p(c).edit().putString("tasks", a.toString()).apply()
    }

    fun loadInbox(c: Context): List<Sug> {
        val a = JSONArray(p(c).getString("inbox", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Sug(o.getString("f"), o.getString("t")) }
    }

    fun saveInbox(c: Context, l: List<Sug>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("f", it.from).put("t", it.text)) }
        p(c).edit().putString("inbox", a.toString()).apply()
    }

    fun addSuggestion(c: Context, s: Sug) {
        val l = loadInbox(c)
        if (l.any { it.text == s.text }) return
        saveInbox(c, (listOf(s) + l).take(20))
    }

    fun captured(c: Context) = p(c).getInt("cap", 0)
    fun bump(c: Context, n: Int) { p(c).edit().putInt("cap", captured(c) + n).apply() }
}
