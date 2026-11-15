// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import org.junit.Assert.*
import org.junit.Test

class NotificationActionQueueTest {
    @Test fun replayNeverReplacesTheOriginalReply() {
        val queue = NotificationActionQueue<String>()
        assertEquals(NotificationActionQueue.Offer.ACCEPTED, queue.offer("reply:id", "original"))
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("reply:id", "changed"))
        assertEquals("original", queue.next()?.second)
        assertNull(queue.next())
        queue.complete("reply:id")
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("reply:id", "original"))
    }
    @Test fun pendingActionsKeepOrderAndDoNotOverlap() {
        val queue = NotificationActionQueue<String>()
        queue.offer("a", "first"); queue.offer("b", "second")
        assertEquals("a", queue.next()?.first)
        queue.complete("wrong")
        assertNull(queue.next())
        queue.complete("a")
        assertEquals("b", queue.next()?.first)
    }
    @Test fun timeoutCannotCancelAnAttemptAlreadySubmitted() {
        val queue = NotificationActionQueue<String>()
        queue.offer("a", "active"); queue.offer("b", "waiting")
        queue.next()
        assertNull(queue.expire("a"))
        assertEquals("waiting", queue.expire("b"))
        queue.complete("a")
        assertNull(queue.next())
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("b", "retry"))
    }
    @Test fun fullQueueDoesNotConsumeRejectedAction() {
        val queue = NotificationActionQueue<String>(capacity = 1)
        queue.offer("a", "first"); queue.next()
        assertEquals(NotificationActionQueue.Offer.FULL, queue.offer("b", "second"))
        queue.complete("a")
        assertEquals(NotificationActionQueue.Offer.ACCEPTED, queue.offer("b", "second"))
    }
    @Test fun disconnectCancelsWorkWithoutAllowingReplayAndForgetResetsHistory() {
        val queue = NotificationActionQueue<String>()
        queue.offer("a", "first"); queue.next(); queue.offer("b", "second")
        val cancelled = queue.cancel()
        assertEquals(listOf("second" to false, "first" to true), cancelled)
        assertNull(queue.next())
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("b", "second"))
        queue.cancel(clearHistory = true)
        assertEquals(NotificationActionQueue.Offer.ACCEPTED, queue.offer("b", "new session"))
    }
    @Test fun historyTrimmingRetainsActiveAndWaitingActions() {
        val queue = NotificationActionQueue<String>(capacity = 2, remembered = 2)
        for (id in listOf("old1", "old2")) { queue.offer(id, id); queue.next(); queue.complete(id) }
        queue.offer("active", "a"); queue.next(); queue.offer("waiting", "b")
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("active", "duplicate"))
        assertEquals(NotificationActionQueue.Offer.DUPLICATE, queue.offer("waiting", "duplicate"))
    }
}
