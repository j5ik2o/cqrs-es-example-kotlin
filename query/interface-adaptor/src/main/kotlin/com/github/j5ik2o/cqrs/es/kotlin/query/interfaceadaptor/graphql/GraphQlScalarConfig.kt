package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.graphql.execution.RuntimeWiringConfigurer

@Configuration
@Profile("read")
class GraphQlScalarConfig {
    @Bean
    fun naiveDateTimeConfigurer(): RuntimeWiringConfigurer =
        RuntimeWiringConfigurer { wiring -> wiring.scalar(NaiveDateTimeScalar.create()) }
}
