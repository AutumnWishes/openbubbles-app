// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

/**
 * Decides which bridge events represent a newly received message, independently
 * of Android's notification API. Keep one instance across reconnects; create a
 * new instance when the saved account is forgotten/replaced.
 *
 * The baseline prevents a newly started process from notifying the inbox history.
 * IDs are retained only in memory, with a fixed bound; no message bodies or
 * account credentials are needed. Call before suppressing a visible chat so its
 * subsequent delivery updates cannot produce a second notification.
 */
internal class IncomingNotificationPolicy(
    private val startedAtMs: Long = System.currentTimeMillis(),
    private val maxEntries: Int = 512,
) {
    init { require(maxEntries > 0) }

    private val seen = LinkedHashSet<Pair<String, String>>()

    @Synchronized
    fun shouldNotify(
        id: String,
        conversationId: String,
        outgoing: Boolean,
        old: Boolean,
        statusCode: Int,
        timestampMs: Long,
        nowMs: Long,
    ): Boolean {
        if (id.isBlank() || conversationId.isBlank() || id.length > 512 || conversationId.length > 512) return false
        val key = conversationId to id
        if (key in seen || outgoing) return false

        // History and already-read messages must stay suppressed if a later
        // status-only update arrives for the same ID without the old flag.
        if (old || statusCode == INCOMING_DISPLAYED) {
            remember(key)
            return false
        }

        // The pinned protocol includes download states and system tombstones
        // among incoming events. Wait for actual receipt, not just a status.
        if (statusCode != INCOMING_COMPLETE && statusCode != INCOMING_DELIVERED) return false
        if (timestampMs <= 0 || timestampMs < startedAtMs || timestampMs > nowMs + MAX_FUTURE_SKEW_MS ||
            nowMs - timestampMs > MAX_AGE_MS) return false

        remember(key)
        return true
    }

    private fun remember(key: Pair<String, String>) {
        seen.add(key)
        if (seen.size > maxEntries) {
            val iterator = seen.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        // gmproto.MessageStatusType in the pinned Google Messages library.
        const val INCOMING_COMPLETE = 100
        const val INCOMING_DELIVERED = 108
        const val INCOMING_DISPLAYED = 109
        const val MAX_AGE_MS = 5 * 60 * 1000L
        const val MAX_FUTURE_SKEW_MS = 2 * 60 * 1000L
    }
}
