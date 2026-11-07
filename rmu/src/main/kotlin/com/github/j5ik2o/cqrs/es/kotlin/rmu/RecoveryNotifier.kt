package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Delivers a recovery notification. SQS is used only to wake a worker; the message carries
 * identifiers, never the event body. The durable task in the recovery store is the source of truth.
 */
fun interface RecoveryNotifier {
    fun notifyTask(task: RecoveryTask)
}
