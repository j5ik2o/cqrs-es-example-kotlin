package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ResyncWorkerTest {
    @Test
    fun `returns the resync counts`() {
        val worker = ResyncWorker(ResyncRunner { ResyncResult(heads = 3, skipped = 1, advanced = 2, failed = 0) })

        val response = worker.handleRequest(emptyMap<String, Any>(), testContext())

        assertEquals(3, response["heads"])
        assertEquals(1, response["skipped"])
        assertEquals(2, response["advanced"])
        assertEquals(0, response["failed"])
    }
}
