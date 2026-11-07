package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Returns the store identifier shared by the three DynamoDB tables. */
fun interface StoreIdProvider {
    fun storeId(): String
}
