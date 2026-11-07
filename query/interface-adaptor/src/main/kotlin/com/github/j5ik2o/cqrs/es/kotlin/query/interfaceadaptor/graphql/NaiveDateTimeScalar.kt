package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object NaiveDateTimeScalar {
    const val NAME = "NaiveDateTime"

    private val FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun create(): GraphQLScalarType =
        GraphQLScalarType
            .newScalar()
            .name(NAME)
            .description("ISO 8601 combined date and time without timezone.")
            .coercing(
                object : Coercing<LocalDateTime, String> {
                    override fun serialize(
                        input: Any,
                        context: GraphQLContext,
                        locale: Locale,
                    ): String {
                        if (input is LocalDateTime) {
                            return FORMATTER.format(input)
                        }
                        throw CoercingSerializeException(
                            "Expected a java.time.LocalDateTime but was ${typeNameOf(input)}",
                        )
                    }

                    override fun parseValue(
                        input: Any,
                        context: GraphQLContext,
                        locale: Locale,
                    ): LocalDateTime = parse(input, ::CoercingParseValueException)

                    override fun parseLiteral(
                        input: Value<*>,
                        variables: CoercedVariables,
                        context: GraphQLContext,
                        locale: Locale,
                    ): LocalDateTime {
                        if (input is StringValue) {
                            return parse(input.value, ::CoercingParseLiteralException)
                        }
                        throw CoercingParseLiteralException(
                            "Expected a StringValue but was ${typeNameOf(input)}",
                        )
                    }

                    override fun valueToLiteral(
                        input: Any,
                        context: GraphQLContext,
                        locale: Locale,
                    ): Value<*> = StringValue.of(serialize(input, context, locale))
                },
            ).build()

    private fun <E : RuntimeException> parse(
        input: Any?,
        errorFactory: (String) -> E,
    ): LocalDateTime {
        if (input is String) {
            try {
                return LocalDateTime.parse(input, FORMATTER)
            } catch (e: DateTimeParseException) {
                throw errorFactory("Invalid $NAME value '$input': ${e.message}")
            }
        }
        throw errorFactory("Expected a String but was ${typeNameOf(input)}")
    }

    private fun typeNameOf(value: Any?): String = value?.javaClass?.name ?: "null"
}
