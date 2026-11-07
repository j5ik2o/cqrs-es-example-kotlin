package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.Context
import java.lang.reflect.Proxy

/** A no-op Lambda context; the handlers under test do not read it. */
internal fun testContext(): Context =
    Proxy.newProxyInstance(
        Context::class.java.classLoader,
        arrayOf(Context::class.java),
    ) { _, method, _ -> if (method.returnType == Void.TYPE) Unit else null } as Context
