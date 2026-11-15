// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshEventPolicyTest {
    private fun RefreshEventPolicy.event(
        kind: String = "message",
        id: String = "message-one",
        conversation: String = "chat-one",
        signature: String = "received-message",
        old: Boolean = false,
        selected: String? = "chat-one",
    ) = shouldRefresh(kind, id, conversation, signature, old, selected)

    @Test fun repeatedIdenticalMessageDoesNotRequestAnotherRead() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event())
        repeat(100) { assertFalse(policy.event()) }
    }

    @Test fun unchangedSendObservationsDoNotKeepRefreshing() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event(kind = "send", id = "request", conversation = "", signature = "RCS|delivered"))
        repeat(100) {
            assertFalse(policy.event(kind = "send", id = "request", conversation = "", signature = "RCS|delivered"))
        }
    }

    @Test fun actualDeliveryOrContentChangesInvalidateOnce() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event(kind = "send", id = "request", signature = "RCS|accepted"))
        assertTrue(policy.event(kind = "send", id = "request", signature = "RCS|delivered"))
        assertFalse(policy.event(kind = "send", id = "request", signature = "RCS|delivered"))
        assertTrue(policy.event())
        assertTrue(policy.event(signature = "edited-message"))
        assertFalse(policy.event(signature = "edited-message"))
    }

    @Test fun historyNeverInvalidatesAndSeedsIdenticalLiveReplay() {
        val policy = RefreshEventPolicy()
        assertFalse(policy.event(old = true))
        assertFalse(policy.event())
        assertTrue(policy.event(signature = "new-content"))
    }

    @Test fun lateHistoryCannotRollBackLatestFingerprint() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event(signature = "delivered"))
        assertFalse(policy.event(signature = "sent", old = true))
        assertFalse(policy.event(signature = "delivered"))
    }

    @Test fun anotherChatDoesNotRefreshCurrentChat() {
        val policy = RefreshEventPolicy()
        for (kind in listOf("message", "conversation", "send")) {
            assertFalse(policy.event(kind = kind, conversation = "chat-two"))
        }
    }

    @Test fun irrelevantEventIsRememberedBeforeNavigation() {
        val policy = RefreshEventPolicy()
        assertFalse(policy.event(conversation = "chat-two"))
        // Navigation itself loads the chat; the event's replay is not new data.
        assertFalse(policy.event(conversation = "chat-two", selected = "chat-two"))
        assertTrue(policy.event(conversation = "chat-two", selected = "chat-two", signature = "new-data"))
    }

    @Test fun inboxAcceptsChangedEventsFromAnyConversation() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event(id = "first", conversation = "chat-one", selected = null))
        assertTrue(policy.event(id = "second", conversation = "chat-two", selected = null))
        assertFalse(policy.event(id = "second", conversation = "chat-two", selected = null))
    }

    @Test fun eventKindsHaveIndependentIdentity() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event(kind = "message", id = "same"))
        assertTrue(policy.event(kind = "conversation", id = "same"))
        assertTrue(policy.event(kind = "send", id = "same"))
        assertFalse(policy.event(kind = "message", id = "same"))
    }

    @Test fun boundedRetentionKeepsRecentlyObservedKeys() {
        val policy = RefreshEventPolicy(maxEntries = 2)
        assertTrue(policy.event(id = "first"))
        assertTrue(policy.event(id = "second"))
        assertFalse(policy.event(id = "first")) // first becomes most recent
        assertTrue(policy.event(id = "third"))
        assertFalse(policy.event(id = "first"))
        assertTrue(policy.event(id = "second")) // least-recent key was evicted
    }

    @Test fun clearStartsFreshForNewConnectionOrAccount() {
        val policy = RefreshEventPolicy()
        assertTrue(policy.event())
        assertFalse(policy.event())
        policy.clear()
        assertTrue(policy.event())
    }

    @Test fun malformedEventsDoNotTriggerReads() {
        val policy = RefreshEventPolicy()
        assertFalse(policy.event(kind = "unknown"))
        assertFalse(policy.event(id = ""))
        assertFalse(policy.event(id = "x".repeat(513)))
        assertFalse(policy.event(conversation = ""))
        assertFalse(policy.event(conversation = "x".repeat(513)))
    }
}
