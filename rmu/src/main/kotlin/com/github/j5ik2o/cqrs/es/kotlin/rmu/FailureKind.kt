package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Why an event could not be applied. The kind is stored with a recovery task so an operator can
 * tell a data problem from a storage outage.
 */
enum class FailureKind {
    EVENT_PAYLOAD,
    UNSUPPORTED_EVENT,
    JOURNAL_GAP,
    READ_MODEL_CONSTRAINT,
    RECOVERY_TASK_STORE,
    UNKNOWN,
    ;

    fun asString(): String = name.lowercase()

    companion object {
        fun fromString(value: String?): FailureKind {
            if (value.isNullOrBlank()) return UNKNOWN
            return runCatching { valueOf(value.uppercase()) }.getOrDefault(UNKNOWN)
        }
    }
}
