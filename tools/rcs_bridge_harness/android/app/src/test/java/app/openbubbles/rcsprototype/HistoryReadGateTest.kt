// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import org.junit.Assert.*
import org.junit.Test

class HistoryReadGateTest {
    @Test fun eventBurstCoalescesIntoOneFollowUp() {
        val gate = HistoryReadGate()
        repeat(20) { gate.request() }
        val first = requireNotNull(gate.begin(blocked = false))
        assertFalse(gate.pending)
        repeat(20) { gate.request() }
        assertTrue(gate.finish(first))
        assertTrue(gate.pending)
        val followUp = requireNotNull(gate.begin(blocked = false))
        assertFalse(gate.pending)
        assertTrue(gate.finish(followUp))
        assertFalse(gate.inFlight)
        assertFalse(gate.pending)
    }

    @Test fun secondReadWaitsForFirstAndRetainsOneRequest() {
        val gate = HistoryReadGate()
        val first = requireNotNull(gate.begin(blocked = false))
        repeat(5) { assertNull(gate.begin(blocked = false)) }
        assertTrue(gate.inFlight)
        assertTrue(gate.pending)
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.begin(blocked = false))
        assertNotEquals(first, second)
        assertTrue(gate.inFlight)
        assertFalse(gate.pending)
    }

    @Test fun manualReadBlockedByExclusiveOperationIsNotLost() {
        val gate = HistoryReadGate()
        assertNull(gate.begin(blocked = true))
        assertFalse(gate.inFlight)
        assertTrue(gate.pending)
        assertNull(gate.begin(blocked = true))
        val id = requireNotNull(gate.begin(blocked = false))
        assertTrue(gate.inFlight)
        assertFalse(gate.pending)
        assertTrue(gate.finish(id))
    }

    @Test fun cancelledCompletionCannotReleaseNewReadOrLoseItsFollowUp() {
        val gate = HistoryReadGate()
        val stale = requireNotNull(gate.begin(blocked = false))
        gate.request()
        gate.cancel()
        assertFalse(gate.inFlight)
        assertFalse(gate.pending)
        val current = requireNotNull(gate.begin(blocked = false))
        gate.request()
        assertNotEquals(stale, current)
        assertFalse(gate.finish(stale))
        assertTrue(gate.inFlight)
        assertTrue(gate.pending)
        assertTrue(gate.finish(current))
        assertFalse(gate.inFlight)
        assertTrue(gate.pending)
    }

    @Test fun duplicateCompletionCannotReleaseFollowingRead() {
        val gate = HistoryReadGate()
        val first = requireNotNull(gate.begin(blocked = false))
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.begin(blocked = false))
        assertFalse(gate.finish(first))
        assertTrue(gate.inFlight)
        assertTrue(gate.finish(second))
        assertFalse(gate.finish(second))
    }

    @Test fun disconnectDropsQueuedWorkWithoutStartingAnotherRead() {
        val gate = HistoryReadGate()
        val first = requireNotNull(gate.begin(blocked = false))
        repeat(10) { gate.request() }
        gate.cancel()
        assertFalse(gate.inFlight)
        assertFalse(gate.pending)
        assertFalse(gate.finish(first))
        assertFalse(gate.pending)
    }
}
