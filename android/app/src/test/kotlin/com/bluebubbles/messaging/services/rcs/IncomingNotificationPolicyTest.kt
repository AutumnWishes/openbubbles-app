// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationPolicyTest {
    private val start = 1_800_000_000_000L
    private val now = start + 10_000

    private fun IncomingNotificationPolicy.event(
        id: String = "message",
        conversation: String = "chat",
        outgoing: Boolean = false,
        old: Boolean = false,
        status: Int = 100,
        timestamp: Long = now,
        at: Long = now,
    ) = shouldNotify(id, conversation, outgoing, old, status, timestamp, at)

    @Test fun completeAndDeliveredIncomingMessagesAreEligible() {
        val policy = IncomingNotificationPolicy(start)
        assertTrue(policy.event(id = "complete", status = 100))
        assertTrue(policy.event(id = "delivered", status = 108))
    }

    @Test fun historyBeforeProcessStartDoesNotFloodNotificationsEvenWithoutOldFlag() {
        val policy = IncomingNotificationPolicy(start)
        repeat(100) { assertFalse(policy.event(id = "history-$it", timestamp = start - it - 1)) }
        assertTrue(policy.event(id = "new-message"))
    }

    @Test fun oldMessagesRemainSuppressedAfterLaterStatusUpdates() {
        val policy = IncomingNotificationPolicy(start)
        assertFalse(policy.event(old = true))
        assertFalse(policy.event(status = 108))
    }

    @Test fun outgoingMessagesAndSystemTombstonesNeverNotify() {
        val policy = IncomingNotificationPolicy(start)
        assertFalse(policy.event(id = "outgoing", outgoing = true))
        for (status in listOf(0, 1, 2, 3, 200, 240, 999)) {
            assertFalse(policy.event(id = "status-$status", status = status))
        }
    }

    @Test fun pendingDownloadCanNotifyWhenItActuallyCompletes() {
        val policy = IncomingNotificationPolicy(start)
        for (status in listOf(101, 102, 103, 104, 105, 106, 107, 110, 111, 112, 113, 114, 115, 116, 117, 118)) {
            assertFalse(policy.event(status = status))
        }
        assertTrue(policy.event(status = 100))
    }

    @Test fun alreadyDisplayedMessageAndLaterReceiptUpdateDoNotNotify() {
        val policy = IncomingNotificationPolicy(start)
        assertFalse(policy.event(status = 109))
        assertFalse(policy.event(status = 100))
    }

    @Test fun repeatedReceiptEventsProduceOnlyOneNotification() {
        val policy = IncomingNotificationPolicy(start)
        assertTrue(policy.event())
        assertFalse(policy.event())
        assertFalse(policy.event(status = 108))
        assertFalse(policy.event(status = 109))
    }

    @Test fun messageIdsAreScopedToConversation() {
        val policy = IncomingNotificationPolicy(start)
        assertTrue(policy.event(conversation = "first"))
        assertTrue(policy.event(conversation = "second"))
        assertFalse(policy.event(conversation = "first"))
    }

    @Test fun shortReconnectCanDeliverNewMessagesReceivedWhileOffline() {
        val policy = IncomingNotificationPolicy(start)
        assertTrue(policy.event(id = "before-disconnect"))
        assertTrue(policy.event(id = "during-disconnect", timestamp = now + 30_000, at = now + 60_000))
        assertFalse(policy.event(id = "before-disconnect", at = now + 60_000))
    }

    @Test fun staleOrInvalidTimestampsAreSuppressed() {
        val policy = IncomingNotificationPolicy(start)
        assertFalse(policy.event(id = "stale", at = now + 300_001))
        assertFalse(policy.event(id = "missing", timestamp = 0))
        assertFalse(policy.event(id = "seconds", timestamp = now / 1000))
        assertFalse(policy.event(id = "microseconds", timestamp = now * 1000))
        assertFalse(policy.event(id = "future", timestamp = now + 120_001))
        assertTrue(policy.event(id = "clock-skew", timestamp = now + 1000))
    }

    @Test fun malformedIdentifiersAreRejected() {
        val policy = IncomingNotificationPolicy(start)
        assertFalse(policy.event(id = ""))
        assertFalse(policy.event(conversation = " "))
        assertFalse(policy.event(id = "x".repeat(513)))
        assertFalse(policy.event(conversation = "x".repeat(513)))
    }

    @Test fun dedupRetentionIsBoundedWithoutDroppingRecentIds() {
        val policy = IncomingNotificationPolicy(start, maxEntries = 2)
        assertTrue(policy.event(id = "first"))
        assertTrue(policy.event(id = "second"))
        assertTrue(policy.event(id = "third"))
        assertFalse(policy.event(id = "second"))
        assertFalse(policy.event(id = "third"))
        // The oldest ID is evicted; retention cannot grow with an endless feed.
        assertTrue(policy.event(id = "first"))
    }
}
