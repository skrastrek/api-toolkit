package io.skrastrek.api.http4k.openapi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass

/**
 * A named API schema entry combining a schema name, its Kotlin class, and its serializer.
 *
 * Used in [OpenApiSchemas] to define the set of named schemas for an API in a single place,
 * eliminating the need to register the same class in both [fest.ly.util.api.openApiRenderer] `schemaHints` and
 * a [SerializersModule].
 */
data class OpenApiSchema<T : Any>(
    val name: String,
    val kClass: KClass<T>,
    val serializer: KSerializer<T>,
) {
    constructor(kClass: KClass<T>, serializer: KSerializer<T>) : this(
        name = kClass.simpleName ?: error("Anonymous class cannot be a schema"),
        kClass = kClass,
        serializer = serializer,
    )
}

/**
 * Returns lens metadata that embeds a `${"$"}ref` to `#/components/schemas/[OpenApiSchema.name]` in
 * the rendered OpenAPI parameter schema. Use this when calling `required()`, `optional()`, or
 * `defaulted()` on a lens to opt into schema-ref replacement by [fest.ly.util.api.openApiRenderer].
 *
 * The schema itself must be registered in [OpenApiSchemas] passed to [fest.ly.util.api.openApiRenderer] so the renderer
 * can derive and inject its definition into `components/schemas`.
 */
fun OpenApiSchema<*>.asParameterMetadata(): Map<String, Any> = mapOf("schema" to mapOf($$"$ref" to "#/components/schemas/$name"))

/**
 * A collection of [OpenApiSchema] entries that derives both a `schemaHints` map (for [fest.ly.util.api.openApiRenderer])
 * and a [SerializersModule] (for [org.http4k.format.ConfigurableKotlinxSerialization]).
 *
 * Multiple entries may reference the same class (e.g. `CreateCollection` and `UpdateCollection`
 * both backed by the same request class). The [serializersModule] deduplicates by class, so each
 * class is registered as a contextual serializer at most once.
 */
class OpenApiSchemas(
    val entries: List<OpenApiSchema<*>>,
) {
    val schemaHints: Map<String, KClass<*>> = entries.associate { it.name to it.kClass }

    fun serializersModule(): SerializersModule =
        SerializersModule {
            val seen = mutableSetOf<KClass<*>>()
            entries.forEach { entry ->
                if (seen.add(entry.kClass)) {
                    @Suppress("UNCHECKED_CAST")
                    contextual(entry.kClass as KClass<Any>, entry.serializer as KSerializer<Any>)
                }
            }
        }

    companion object {
        operator fun invoke(vararg entries: OpenApiSchema<*>): OpenApiSchemas = OpenApiSchemas(entries.toList())
    }
}
