package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Walks the whole head table. DY-15 requires a strongly consistent read so a resync sees every
 * aggregate that advanced while the reader was stopped.
 */
fun interface HeadScanner {
    fun scanHeads(visitor: (Head) -> Unit)
}
