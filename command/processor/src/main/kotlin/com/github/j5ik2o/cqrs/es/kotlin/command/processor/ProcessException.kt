package com.github.j5ik2o.cqrs.es.kotlin.command.processor

sealed class ProcessException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundProcessException(
    message: String,
) : ProcessException(message)
