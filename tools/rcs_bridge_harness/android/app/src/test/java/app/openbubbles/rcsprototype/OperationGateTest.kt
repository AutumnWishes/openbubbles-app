// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGateTest {
    @Test fun responseArrivingAfterForgetCannotRestorePrivateHistory() {
        val gate = OperationGate()
        val pendingFetch = gate.token()
        var displayedHistory = "old account"
        gate.replaceSession()
        displayedHistory = ""
        if (gate.accepts(pendingFetch)) displayedHistory = "late private messages"
        assertEquals("", displayedHistory)
        assertFalse(gate.sameSession(pendingFetch))
    }

    @Test fun disconnectRevokesPendingSendResultButKeepsEncryptedSessionEligible() {
        val gate = OperationGate()
        val pendingSend = gate.token()
        gate.cancelOperations()
        assertFalse(gate.accepts(pendingSend))
        assertTrue(gate.sameSession(pendingSend))
        assertTrue(gate.accepts(gate.token()))
    }

    @Test fun repeatedDisconnectAndNewLoginNeverRevalidateOldCallbacks() {
        val gate = OperationGate()
        val first = gate.token()
        gate.cancelOperations()
        val second = gate.token()
        gate.replaceSession()
        val third = gate.token()
        assertFalse(gate.accepts(first))
        assertFalse(gate.accepts(second))
        assertFalse(gate.sameSession(second))
        assertTrue(gate.accepts(third))
    }

    @Test fun queuedListenerEventCannotOverwriteAReconnectedSession() {
        val gate = OperationGate()
        val listenerSession = gate.token()
        val receivedEvent = gate.token()
        var state = "ready"
        val deliverOldEvent = {
            if (gate.sameSession(listenerSession) && gate.accepts(receivedEvent)) state = "old offline event"
        }
        gate.cancelOperations()
        state = "new connection ready"
        deliverOldEvent()
        assertEquals("new connection ready", state)
        assertTrue(gate.sameSession(listenerSession))
    }
}
