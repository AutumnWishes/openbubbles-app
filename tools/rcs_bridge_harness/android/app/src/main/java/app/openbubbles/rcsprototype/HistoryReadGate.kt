// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

/**
 * Main-thread coordinator for read-only history refreshes. A burst of requests
 * retains one follow-up, while request IDs prevent cancelled reads from releasing
 * a newer read. It is deliberately independent of exclusive-operation busy state.
 */
internal class HistoryReadGate {
    private var sequence = 0L
    private var active: Long? = null

    val inFlight: Boolean get() = active != null
    var pending: Boolean = false
        private set

    fun request() { pending = true }

    fun begin(blocked: Boolean): Long? {
        if (blocked || inFlight) {
            pending = true
            return null
        }
        val id = ++sequence
        active = id
        pending = false
        return id
    }

    fun finish(id: Long): Boolean {
        if (active != id) return false
        active = null
        return true
    }

    fun cancel() {
        sequence++
        active = null
        pending = false
    }
}
