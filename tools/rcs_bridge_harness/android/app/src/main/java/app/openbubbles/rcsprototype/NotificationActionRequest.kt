// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import android.app.RemoteInput
import android.content.Intent
import java.util.UUID

/** Routing comes only from the fixed PendingIntent URI, never fill-in extras. */
internal data class NotificationActionRequest(
    val reply: Boolean,
    val conversationId: String,
    val messageId: String,
    val token: String,
    val text: String,
) {
    val key: String get() = "${if (reply) "reply" else "read"}:$token"

    companion object {
        fun fromIntent(intent: Intent): NotificationActionRequest? {
            val route = routeFromIntent(intent) ?: return null
            if (!route.reply) return route
            val value = try { RemoteInput.getResultsFromIntent(intent)?.getCharSequence(BridgeNotifications.REPLY_TEXT)?.toString() }
                catch (_: RuntimeException) { null }
            val text = value?.trim() ?: return null
            if (text.isEmpty() || text.length > 4000 || text.toByteArray(Charsets.UTF_8).size > 16 * 1024) return null
            return route.copy(text = text)
        }

        /** Allows a valid action to receive failure feedback even for invalid input. */
        fun routeFromIntent(intent: Intent): NotificationActionRequest? {
            val reply = when (intent.action) {
                BridgeNotifications.REPLY_ACTION -> true
                BridgeNotifications.MARK_READ_ACTION -> false
                else -> return null
            }
            val uri = intent.data ?: return null
            if (uri.scheme != "rcsa" || uri.host != if (reply) "reply" else "read") return null
            if (uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val parts = uri.pathSegments
            if (parts.size != 3 || !validId(parts[0]) || !validId(parts[1])) return null
            val token = try { UUID.fromString(parts[2]).toString() } catch (_: IllegalArgumentException) { return null }
            if (token != parts[2]) return null
            return NotificationActionRequest(reply, parts[0], parts[1], token, "")
        }

        private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 512 && value.none { it.code < 32 || it.code == 127 }
    }
}
