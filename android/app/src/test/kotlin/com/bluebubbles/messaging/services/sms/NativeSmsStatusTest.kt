package com.bluebubbles.messaging.services.sms

/** Run with kotlinc NativeSmsStatus.kt NativeSmsStatusTest.kt -include-runtime -d sms-tests.jar. */
fun main() {
    fun state(parts: Int, vararg callbacks: Pair<Int, Int>) = NativeSmsStatus.aggregate(parts, callbacks.toMap()).state
    val pending = NativeSmsStatus.State.PENDING
    val sent = NativeSmsStatus.State.SENT
    val failed = NativeSmsStatus.State.FAILED

    check(state(1) == pending) // No callback never means success.
    check(state(1, 0 to -1) == sent)
    check(state(3, 2 to -1, 0 to -1) == pending) // Out-of-order successes remain pending.
    check(state(3, 2 to -1, 0 to -1, 1 to -1) == sent)
    check(state(2, 0 to -1, 0 to -1) == pending) // Repeated callbacks cannot complete another part.
    check(state(2, 0 to -1, 9 to -1) == pending) // Unrelated/invalid indexes never count.
    check(state(2, -1 to -1, 1 to -1) == pending)
    check(state(3, 0 to -1, 2 to 4) == failed) // One failed segment makes the entire SMS failed.
    check(state(3, 0 to -1, 2 to 4, 1 to -1) == failed) // Later successes cannot undo failure.
    check(NativeSmsStatus.aggregate(2, mapOf(0 to 2)).errorCode == 2)
    check(runCatching { NativeSmsStatus.aggregate(0, emptyMap()) }.isFailure)
    check(!NativeSmsStatus.isUnconfirmedTooLong(1000, 120999))
    check(NativeSmsStatus.isUnconfirmedTooLong(1000, 121000))
    check(!NativeSmsStatus.isUnconfirmedTooLong(1000, 999)) // Clock rollback does not imply failure.
    println("Native SMS multipart status: 14 checks passed")
}
