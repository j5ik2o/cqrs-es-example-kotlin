package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Signals that a recovery pass did not complete every pending task. */
class RecoveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
