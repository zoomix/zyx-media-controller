package com.zyx.mediacontroller.phone

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log

object NotificationActionRegistry {
    private const val logTag = "NotificationActions"

    private val lock = Any()
    private var actionsByPackageName: Map<String, List<CachedNotificationAction>> = emptyMap()

    fun updateFrom(notifications: Array<StatusBarNotification>) {
        val nextActions = notifications
            .mapNotNull { notification ->
                val actions = notification.notification.actions
                    ?.mapNotNull { action -> action.toCachedAction() }
                    .orEmpty()
                if (actions.isEmpty()) null else notification.packageName to actions
            }
            .toMap()

        synchronized(lock) {
            actionsByPackageName = nextActions
        }
    }

    fun sendSeekBack(context: Context, packageName: String): String? {
        return sendFirstMatching(context, packageName) { action ->
            action.matchesAny("rewind", "seek back", "seek backward", "skip back", "skip backward", "tillbaka", "bakåt")
        }
    }

    fun sendSeekForward(context: Context, packageName: String): String? {
        return sendFirstMatching(context, packageName) { action ->
            action.matchesAny("fast forward", "forward", "seek ahead", "seek forward", "skip ahead", "skip forward", "fram")
        }
    }

    private fun sendFirstMatching(
        context: Context,
        packageName: String,
        predicate: (CachedNotificationAction) -> Boolean,
    ): String? {
        val action = synchronized(lock) {
            actionsByPackageName[packageName].orEmpty().firstOrNull(predicate)
        } ?: return null

        return runCatching {
            action.intent.send(context, 0, null)
            action.title.ifBlank { "(untitled notification action)" }
        }.getOrElse { error ->
            Log.w(logTag, "Failed to send notification action '${action.title}' for $packageName", error)
            null
        }
    }

    private fun Notification.Action.toCachedAction(): CachedNotificationAction? {
        val pendingIntent = actionIntent ?: return null
        return CachedNotificationAction(
            title = title?.toString().orEmpty(),
            intent = pendingIntent,
        )
    }
}

private data class CachedNotificationAction(
    val title: String,
    val intent: PendingIntent,
) {
    fun matchesAny(vararg needles: String): Boolean {
        val haystack = title.lowercase()
        return needles.any { needle -> haystack.contains(needle) }
    }
}
