package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.graphql

import org.springframework.context.annotation.Profile
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Mono

@Controller
@Profile("write")
class HealthCheckQueryResolver {
    @QueryMapping
    fun healthCheck(): Mono<String> = Mono.just("OK")
}
