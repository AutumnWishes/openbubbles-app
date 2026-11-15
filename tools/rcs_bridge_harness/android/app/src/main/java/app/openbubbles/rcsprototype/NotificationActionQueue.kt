// SPDX-License-Identifier: AGPL-3.0-or-later
package app.openbubbles.rcsprototype

/** Main-thread bounded queue. A delivered action is consumed once per process. */
internal class NotificationActionQueue<T>(private val capacity: Int = 8, private val remembered: Int = 512) {
    enum class Offer { ACCEPTED, DUPLICATE, FULL }
    private val waiting = LinkedHashMap<String, T>()
    private val seen = LinkedHashSet<String>()
    private var active: Pair<String, T>? = null

    init { require(capacity > 0 && remembered >= capacity) }

    fun offer(key: String, value: T): Offer {
        if (key in seen) return Offer.DUPLICATE
        if (waiting.size + (if (active == null) 0 else 1) >= capacity) return Offer.FULL
        seen.add(key)
        waiting[key] = value
        trimSeen()
        return Offer.ACCEPTED
    }

    fun next(): Pair<String, T>? {
        if (active != null) return null
        val entry = waiting.entries.firstOrNull() ?: return null
        waiting.remove(entry.key)
        return (entry.key to entry.value).also { active = it }
    }

    fun complete(key: String) { if (active?.first == key) active = null }
    fun expire(key: String): T? = waiting.remove(key)
    fun cancel(clearHistory: Boolean = false): List<Pair<T, Boolean>> {
        val cancelled = waiting.values.map { it to false } + listOfNotNull(active?.second?.let { it to true })
        waiting.clear()
        active = null
        if (clearHistory) seen.clear()
        return cancelled
    }

    private fun trimSeen() {
        val iterator = seen.iterator()
        while (seen.size > remembered && iterator.hasNext()) {
            val key = iterator.next()
            if (key !in waiting && key != active?.first) iterator.remove()
        }
    }
}
