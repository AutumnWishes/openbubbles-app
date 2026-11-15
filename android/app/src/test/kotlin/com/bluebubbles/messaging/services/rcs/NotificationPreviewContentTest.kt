// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationPreviewContentTest {
    @Test fun disabledPreviewsNeverIncludeProvidedNameOrMessage() {
        val content = NotificationPreviewContent.forMessage(false, "Private message", "Private contact")
        assertEquals("New RCS message", content.title)
        assertEquals("Tap to open the conversation.", content.text)
    }

    @Test fun enabledPreviewShowsNormalizedNameAndMessage() {
        val content = NotificationPreviewContent.forMessage(true, "  Hello\n\nthere\t👋 ", "  Test\rcontact ")
        assertEquals("Test contact", content.title)
        assertEquals("Hello there 👋", content.text)
    }

    @Test fun missingOrControlOnlyContentUsesGenericFallbacks() {
        val content = NotificationPreviewContent.forMessage(true, "\u0000\t\n", "  \r\n")
        assertEquals("New RCS message", content.title)
        assertEquals("Tap to open the conversation.", content.text)
    }

    @Test fun longPreviewsAreBoundedWithoutSplittingEmoji() {
        val content = NotificationPreviewContent.forMessage(true, "🙂".repeat(300), "😃".repeat(100))
        assertEquals("🙂".repeat(240) + "…", content.text)
        assertEquals("😃".repeat(80) + "…", content.title)
        assertFalse(content.text.contains('\uFFFD'))
    }
}
