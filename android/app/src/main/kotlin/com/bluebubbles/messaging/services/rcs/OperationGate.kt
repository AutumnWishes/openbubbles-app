// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bluebubbles.messaging.services.rcs

/** Revokes delayed callbacks without confusing a disconnect with a deleted account. */
internal class OperationGate {
    data class Token(val session: Long, val operation: Long)
    private var session = 0L
    private var operation = 0L

    @Synchronized fun token() = Token(session, operation)
    @Synchronized fun accepts(token: Token) = token.session == session && token.operation == operation
    @Synchronized fun sameSession(token: Token) = token.session == session
    @Synchronized fun cancelOperations() { operation++ }
    @Synchronized fun replaceSession() { session++; operation++ }
}
