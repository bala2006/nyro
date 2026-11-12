package com.nyro.companion.access

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional. Only active after the user grants notification access in system settings.
 * NYRO reads active notifications on demand; nothing is stored.
 */
class NyroNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    data class Item(val key: String, val app: String, val pkg: String, val title: String, val text: String, val time: Long, val canReply: Boolean)

    fun items(context: Context): List<Item> {
        val pm = context.packageManager
        return runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { !it.isOngoing || it.notification.category == Notification.CATEGORY_CALL }
            .filter { it.packageName != context.packageName }
            .sortedByDescending { it.postTime }
            .map { sbn ->
                val ex = sbn.notification.extras
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                Item(
                    key = sbn.key,
                    app = label,
                    pkg = sbn.packageName,
                    title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                    time = sbn.postTime,
                    canReply = replyAction(sbn) != null,
                )
            }
    }

    fun reply(context: Context, key: String, message: String): String {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return "Notification no longer exists."
        val action = replyAction(sbn) ?: return "This notification has no reply action."
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(input.resultKey, message) }
        RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)
        return try { action.actionIntent.send(context, 0, intent); "Reply sent through ${sbn.packageName}." }
        catch (e: PendingIntent.CanceledException) { "The app cancelled the reply action." }
    }

    fun open(key: String): Boolean {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return false
        return runCatching { sbn.notification.contentIntent?.send(); true }.getOrDefault(false)
    }

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    companion object {
        @Volatile var instance: NyroNotificationListener? = null
    }
}
