// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

import java.security.MessageDigest

/**
 * Filters event-driven read invalidations; it never starts a read itself.
 *
 * Call with a stable signature of display/delivery fields, excluding the old
 * flag. The bounded LRU retains only digests, not message bodies. Remembering
 * irrelevant events prevents them becoming a new invalidation after navigation;
 * explicit navigation/manual refresh is responsible for loading that view.
 */
internal class RefreshEventPolicy(private val maxEntries: Int = 512) {
    init { require(maxEntries > 0) }

    private val signatures = LinkedHashMap<Pair<String, String>, String>(16, 0.75f, true)

    @Synchronized
    fun shouldRefresh(
        kind: String,
        id: String,
        conversationId: String,
        signature: String,
        old: Boolean,
        selectedConversationId: String?,
    ): Boolean {
        if (kind !in setOf("message", "conversation", "send") || id.isBlank() || id.length > 512 ||
            conversationId.length > 512 || (kind != "send" && conversationId.isBlank())) return false
        val key = kind to id
        val fingerprint = digest(signature)
        val previous = signatures[key]

        if (old) {
            // Seed unseen history, but never replace newer live state with an
            // older observation: that would make its next replay look changed.
            if (previous == null) remember(key, fingerprint)
            return false
        }
        if (previous == fingerprint) return false
        remember(key, fingerprint)

        return selectedConversationId == null || conversationId.isEmpty() || conversationId == selectedConversationId
    }

    @Synchronized
    fun clear() { signatures.clear() }

    private fun remember(key: Pair<String, String>, fingerprint: String) {
        signatures[key] = fingerprint
        if (signatures.size > maxEntries) {
            val iterator = signatures.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
