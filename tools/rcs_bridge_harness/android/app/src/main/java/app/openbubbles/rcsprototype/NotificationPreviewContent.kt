// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

/** Bounded display-only text. Nothing here persists message content. */
internal data class NotificationPreviewContent(val title: String, val text: String) {
    companion object {
        const val GENERIC_TITLE = "New RCS message"
        const val GENERIC_TEXT = "Tap to open the conversation."
        private val spacing = Regex("[\\p{Cc}\\p{Z}\\s]+")

        fun forMessage(enabled: Boolean, body: String, conversationName: String): NotificationPreviewContent {
            if (!enabled) return NotificationPreviewContent(GENERIC_TITLE, GENERIC_TEXT)
            return NotificationPreviewContent(
                normalize(conversationName, 80).ifEmpty { GENERIC_TITLE },
                normalize(body, 240).ifEmpty { GENERIC_TEXT },
            )
        }

        private fun normalize(value: String, maxCodePoints: Int): String {
            val text = value.replace(spacing, " ").trim()
            if (text.codePointCount(0, text.length) <= maxCodePoints) return text
            return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…"
        }
    }
}
