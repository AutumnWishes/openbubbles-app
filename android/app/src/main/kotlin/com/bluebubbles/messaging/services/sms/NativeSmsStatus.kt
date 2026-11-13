package com.bluebubbles.messaging.services.sms

/** Pure multipart aggregation, separate from Android/database code so it can be tested on JVM. */
internal object NativeSmsStatus {
    private const val RESULT_OK = -1 // android.app.Activity.RESULT_OK
    enum class State { PENDING, SENT, FAILED }
    data class Outcome(val state: State, val errorCode: Int? = null)

    fun isUnconfirmedTooLong(queuedAt: Long, now: Long): Boolean = now >= queuedAt && now - queuedAt >= 120000

    fun aggregate(partCount: Int, callbacks: Map<Int, Int>): Outcome {
        require(partCount > 0)
        val valid = callbacks.filterKeys { it in 0 until partCount }
        val failure = valid.values.firstOrNull { it != RESULT_OK }
        if (failure != null) return Outcome(State.FAILED, failure)
        return Outcome(if (valid.size == partCount) State.SENT else State.PENDING)
    }
}
