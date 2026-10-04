package com.actionlens

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Optional auto-capture. Reads message notifications on-device, keeps only ones with a date, time or amount. */
class CaptureService : NotificationListenerService() {
    private val apps = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging", "com.android.mms")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in apps) return
        val e = sbn.notification.extras
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val from = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: sbn.packageName
        val p = Parser.parse(text)
        if (p.due != null || p.amount != null) Store.addSuggestion(this, Sug(from, text))
    }
}
