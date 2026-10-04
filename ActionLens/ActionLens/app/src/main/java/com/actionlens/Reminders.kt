package com.actionlens

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Reminders {
    const val CH = "al_reminders"

    fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "ActionLens reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pi(c: Context, code: Int, i: Intent) =
        PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun code(t: Task) = (t.id % Int.MAX_VALUE).toInt()

    /** 30 min before a timed task, or 9:00 AM on the due day when no time was given. */
    fun schedule(c: Context, t: Task) {
        val due = t.due ?: return
        if (t.done) return
        var at = if (t.hasTime) due - 30 * 60_000L else due - (14 * 60 + 59) * 60_000L
        val now = System.currentTimeMillis()
        if (due <= now) return
        if (at < now) at = now + 3_000L
        val i = Intent(c, ReminderReceiver::class.java).putExtra("title", t.title).putExtra("id", code(t))
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, code(t), i))
    }

    fun cancel(c: Context, t: Task) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, code(t), Intent(c, ReminderReceiver::class.java)))
    }

    /** Daily Focus Brief at 8:00 AM. */
    fun scheduleBrief(c: Context) {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi(c, 7, Intent(c, BriefReceiver::class.java)))
    }

    fun notify(c: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Reminders.ensureChannel(c)
        Reminders.notify(c, i.getIntExtra("id", 1), "Reminder", i.getStringExtra("title") ?: "Task due soon")
    }
}

class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val top = Store.loadTasks(c).filter { !it.done }.sortedBy { it.due ?: Long.MAX_VALUE }.take(3)
        if (top.isEmpty()) return
        Reminders.ensureChannel(c)
        Reminders.notify(c, 7, "Today's focus", top.mapIndexed { n, t -> "${n + 1}. ${t.title}" }.joinToString("\n"))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.ensureChannel(c)
        Reminders.scheduleBrief(c)
        Store.loadTasks(c).forEach { Reminders.schedule(c, it) }
    }
}
