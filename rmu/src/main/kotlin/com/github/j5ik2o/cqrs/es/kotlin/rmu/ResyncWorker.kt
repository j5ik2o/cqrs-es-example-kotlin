package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler

/** Runs the full head reconciliation. */
fun interface ResyncRunner {
    fun resync(): ResyncResult
}

/**
 * Runs a full head resync on demand or on a schedule.
 *
 * A Lambda deployment does not run the local reader's startup scan. When the stream mapping is
 * disabled longer than the retention window, or a failed record can no longer be read from the
 * stream, this worker rebuilds the lag from the journal. The position guard makes a repeated run
 * harmless.
 */
class ResyncWorker(
    private val resyncer: ResyncRunner,
) : RequestHandler<Any, Map<String, Any>> {
    /** Lambda instantiates the handler class with a no-argument constructor. */
    constructor() : this(ResyncRunner { LambdaDependenciesHolder.instance.resyncer.resync() })

    override fun handleRequest(
        input: Any,
        context: Context,
    ): Map<String, Any> {
        val result = resyncer.resync()
        return linkedMapOf(
            "heads" to result.heads,
            "skipped" to result.skipped,
            "advanced" to result.advanced,
            "failed" to result.failed,
        )
    }
}
