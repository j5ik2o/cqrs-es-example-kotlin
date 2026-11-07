package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

class ReadModelNotFoundException(
    resource: String,
) : RuntimeException("$resource not found.")
