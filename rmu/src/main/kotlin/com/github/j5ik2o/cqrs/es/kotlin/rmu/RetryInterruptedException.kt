package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Signals that a retry wait was interrupted, so the caller must stop instead of retrying. */
class RetryInterruptedException(
    cause: InterruptedException,
) : RuntimeException("retry wait was interrupted", cause)
