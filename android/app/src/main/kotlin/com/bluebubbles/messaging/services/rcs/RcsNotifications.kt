// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import com.bluebubbles.messaging.MainActivity
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest
import java.util.UUID

/** Message previews are opt-in; their lock-screen public version stays generic. */
internal class RcsNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("rcs_notification_preferences", Context.MODE_PRIVATE)

    var previewsEnabled: Boolean
        get() = preferences.getBoolean("message_previews", false)
        set(value) {
            preferences.edit().putBoolean("message_previews", value).apply()
            // Remove already-posted bodies when the user turns previews off.
            // The ongoing connection notification must remain in place.
            if (!value) {
                synchronized(actionLock) {
                    // Include alerts queued in this process but not yet visible
                    // in NotificationManager's active-notification snapshot.
                    for (key in actionTokens.keys.toList()) {
                        actionTokens[key] = ""
                        manager.cancel(key, MESSAGE_ID)
                    }
                    for (active in manager.activeNotifications) {
                        if (active.id == MESSAGE_ID) {
                            active.tag?.let { rememberToken(it, "") }
                            manager.cancel(active.tag, active.id)
                        }
                    }
                }
            }
        }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CONNECTION, "Background connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when OpenBubbles RCS is running. Use Disconnect to stop it."
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(MESSAGES, "Incoming RCS messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New incoming messages. Message previews are optional in app settings."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(MESSAGES)?.importance != NotificationManager.IMPORTANCE_NONE)

    private fun builder(channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

    private fun openChat(conversationId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                action = OPEN_CONVERSATION
                data = Uri.Builder().scheme("rcs-bridge").authority("conversation").appendPath(conversationId).build()
                putExtra(CONVERSATION_ID, conversationId)
            }
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun connection(state: String): Notification {
        val text = when (state) {
            "ready" -> "Connected in background • tap to open"
            "offline" -> "Waiting for the linked phone • tap to reconnect"
            "reauth_required", "error" -> "Connection needs attention • tap to open"
            "pairing", "awaiting_confirmation" -> "Pairing in progress • tap to continue"
            else -> "Connecting to Google Messages"
        }
        val stop = PendingIntent.getService(context, 1,
            Intent(context, RcsBridgeService::class.java).setAction(RcsBridgeService.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder(CONNECTION).setContentTitle("OpenBubbles RCS").setContentText(text)
            .setContentIntent(openChat()).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_LOW)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
    }

    fun updateConnection(state: String) {
        try { manager.notify(CONNECTION_ID, connection(state)) } catch (_: SecurityException) { }
    }

    private fun publicNotification(open: PendingIntent): Notification = builder(MESSAGES)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentTitle(NotificationPreviewContent.GENERIC_TITLE)
        .setContentText(NotificationPreviewContent.GENERIC_TEXT)
        .setContentIntent(open).setAutoCancel(true)
        .setCategory(Notification.CATEGORY_MESSAGE).build()

    private fun actionIntent(conversationId: String, messageId: String, token: String, reply: Boolean): PendingIntent {
        // RemoteInput requires a mutable PendingIntent. All routing fields are
        // already fixed in this explicit intent, so fill-in extras carry only
        // the reply input. The service validates the data URI, not extras.
        val intent = Intent(context, RcsBridgeService::class.java).apply {
            action = if (reply) REPLY_ACTION else MARK_READ_ACTION
            data = Uri.Builder().scheme("rcsa").authority(if (reply) "reply" else "read")
                .appendPath(conversationId).appendPath(messageId).appendPath(token).build()
        }
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (reply) mutable else PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 0, intent, flags)
            else PendingIntent.getService(context, 0, intent, flags)
    }

    private fun notificationAction(conversationId: String, messageId: String, token: String, reply: Boolean): Notification.Action {
        val action = Notification.Action.Builder(null, if (reply) "Reply" else "Mark as read",
            actionIntent(conversationId, messageId, token, reply))
            .setAllowGeneratedReplies(false)
        if (reply) action.addRemoteInput(RemoteInput.Builder(REPLY_TEXT).setLabel("Reply").build())
        if (Build.VERSION.SDK_INT >= 28) action.setSemanticAction(
            if (reply) Notification.Action.SEMANTIC_ACTION_REPLY else Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        return action.build()
    }

    fun incoming(conversationId: String, previewText: String = "", conversationName: String = "", messageId: String = "") {
        if (!allowed() || !validId(conversationId)) return
        val content = NotificationPreviewContent.forMessage(previewsEnabled, previewText, conversationName)
        val open = openChat(conversationId)
        val token = UUID.randomUUID().toString()
        val notification = builder(MESSAGES).setContentTitle(content.title)
            .setContentText(content.text).setContentIntent(open)
            .setPublicVersion(publicNotification(open))
            .addExtras(Bundle().apply {
                putString(ACTION_TOKEN, token)
                if (validId(messageId)) putString(MESSAGE_ID_EXTRA, messageId)
            })
            .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
            .setDefaults(Notification.DEFAULT_ALL)
        if (validId(messageId)) {
            notification.addAction(notificationAction(conversationId, messageId, token, reply = false))
            notification.addAction(notificationAction(conversationId, messageId, token, reply = true))
        }
        synchronized(actionLock) {
            val key = tag(conversationId)
            rememberToken(key, token)
            try { manager.notify(key, MESSAGE_ID, notification.build()) } catch (_: SecurityException) { }
        }
    }

    fun actionProgress(conversationId: String, token: String, reply: Boolean) {
        updateAction(conversationId, token, if (reply) "Sending reply…" else "Marking as read…",
            "You can keep using your phone.", inProgress = true, reply = reply)
    }

    /** Process death drops in-memory work; never leave an old action spinning. */
    fun recoverInterruptedActions() {
        for (active in manager.activeNotifications) {
            if (active.id != MESSAGE_ID) continue
            val extras = active.notification.extras
            if (!extras.getBoolean(ACTION_IN_PROGRESS)) continue
            val conversationId = extras.getString(CONVERSATION_ID).orEmpty()
            val token = extras.getString(ACTION_TOKEN).orEmpty()
            if (!validId(conversationId) || token.isEmpty()) continue
            val reply = extras.getBoolean(ACTION_IS_REPLY)
            // A previous process may have submitted the reply before dying.
            // Reconcile the notification only; never submit or retry any action.
            actionFinished(conversationId, token, reply, success = false, uncertain = reply)
        }
    }

    fun actionFinished(conversationId: String, token: String, reply: Boolean, success: Boolean, uncertain: Boolean = false) {
        if (!reply && success) {
            synchronized(actionLock) {
                if (matchingAction(conversationId, token) == null) return
                val key = tag(conversationId)
                rememberToken(key, "")
                manager.cancel(key, MESSAGE_ID)
            }
            return
        }
        val title = when {
            reply && success -> "Reply submitted"
            reply && uncertain -> "Check reply status"
            reply -> "Reply not sent"
            else -> "Could not mark as read"
        }
        val text = when {
            reply && success -> "Accepted by the phone. Tap to open the conversation."
            reply && uncertain -> "Open the conversation to check before sending again."
            else -> "Tap to open the conversation."
        }
        updateAction(conversationId, token, title, text)
    }

    private fun updateAction(conversationId: String, token: String, title: String, text: String,
        inProgress: Boolean = false, reply: Boolean = false) {
        synchronized(actionLock) {
            val previous = matchingAction(conversationId, token) ?: return
            val open = openChat(conversationId)
            // Replacing the notification ends the system's inline-reply spinner.
            // Status updates carry no preview/reply body and offer no automatic retry.
            val updated = builder(MESSAGES).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setPublicVersion(publicNotification(open))
                .setOnlyAlertOnce(true).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
                .addExtras(Bundle().apply {
                    putString(ACTION_TOKEN, token)
                    putString(MESSAGE_ID_EXTRA, previous.extras.getString(MESSAGE_ID_EXTRA))
                    putBoolean(ACTION_IN_PROGRESS, inProgress)
                    if (inProgress) {
                        putBoolean(ACTION_IS_REPLY, reply)
                        putString(CONVERSATION_ID, conversationId)
                    }
                }).build()
            try { manager.notify(tag(conversationId), MESSAGE_ID, updated) } catch (_: SecurityException) { }
        }
    }

    /** A completion for an older alert must never replace or cancel a newer one. */
    private fun matchingAction(conversationId: String, token: String): Notification? {
        if (!validId(conversationId) || token.isBlank()) return null
        val key = tag(conversationId)
        val known = actionTokens[key]
        if (known != null && known != token) return null
        return manager.activeNotifications.firstOrNull {
            it.id == MESSAGE_ID && it.tag == key && it.notification.extras.getString(ACTION_TOKEN) == token
        }?.notification
    }

    fun clearConversation(id: String) {
        synchronized(actionLock) {
            val key = tag(id)
            rememberToken(key, "")
            manager.cancel(key, MESSAGE_ID)
        }
    }
    fun clearAll() {
        synchronized(actionLock) {
            for (key in actionTokens.keys.toList()) actionTokens[key] = ""
            for (active in manager.activeNotifications) {
                if (active.id == MESSAGE_ID) active.tag?.let { rememberToken(it, "") }
            }
            for (active in manager.activeNotifications) {
                if (active.id == CONNECTION_ID || active.id == MESSAGE_ID) manager.cancel(active.tag, active.id)
            }
        }
    }
    private fun tag(id: String): String = "openbubbles-rcs:" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= 512 && id.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun rememberToken(key: String, token: String) {
        actionTokens[key] = token
        if (actionTokens.size > 512) {
            val oldest = actionTokens.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    companion object {
        private val actionLock = Any()
        private val actionTokens = LinkedHashMap<String, String>(16, 0.75f, true)
        const val CONNECTION_ID = 821401
        private const val MESSAGE_ID = 821402
        private const val CONNECTION = "openbubbles_rcs_connection"
        private const val MESSAGES = "openbubbles_rcs_messages"
        const val OPEN_CONVERSATION = "com.openbubbles.rcs.OPEN_CONVERSATION"
        const val CONVERSATION_ID = "conversation_id"
        const val REPLY_ACTION = "com.openbubbles.rcs.REPLY"
        const val MARK_READ_ACTION = "com.openbubbles.rcs.MARK_READ"
        const val REPLY_TEXT = "rcs_reply_text"
        const val ACTION_TOKEN = "rcs_action_token"
        const val MESSAGE_ID_EXTRA = "rcs_message_id"
        const val ACTION_IN_PROGRESS = "rcs_action_in_progress"
        const val ACTION_IS_REPLY = "rcs_action_reply"
    }
}
