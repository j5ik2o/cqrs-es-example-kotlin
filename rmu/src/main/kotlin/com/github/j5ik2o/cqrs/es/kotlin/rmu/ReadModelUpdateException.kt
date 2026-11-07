package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Signals that a head stream record or a journal event could not be applied to the read model. */
open class ReadModelUpdateException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
