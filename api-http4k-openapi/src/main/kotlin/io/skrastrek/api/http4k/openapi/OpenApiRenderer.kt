@file:OptIn(ExperimentalSerializationApi::class)

package io.skrastrek.api.http4k.openapi

import io.skrastrek.api.openapi.annotations.Format
import io.skrastrek.api.openapi.annotations.MaxItems
import io.skrastrek.api.openapi.annotations.MaxLength
import io.skrastrek.api.openapi.annotations.Maximum
import io.skrastrek.api.openapi.annotations.MinItems
import io.skrastrek.api.openapi.annotations.MinLength
import io.skrastrek.api.openapi.annotations.Minimum
import io.skrastrek.api.openapi.annotations.Pattern
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind.SEALED
import kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN
import kotlinx.serialization.descriptors.PrimitiveKind.BYTE
import kotlinx.serialization.descriptors.PrimitiveKind.CHAR
import kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE
import kotlinx.serialization.descriptors.PrimitiveKind.FLOAT
import kotlinx.serialization.descriptors.PrimitiveKind.INT
import kotlinx.serialization.descriptors.PrimitiveKind.LONG
import kotlinx.serialization.descriptors.PrimitiveKind.SHORT
import kotlinx.serialization.descriptors.PrimitiveKind.STRING
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.SerialKind.ENUM
import kotlinx.serialization.descriptors.StructureKind.CLASS
import kotlinx.serialization.descriptors.StructureKind.LIST
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.v3.JsonToJsonSchema
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.OpenApi3ApiRenderer
import org.http4k.format.AutoMarshallingJson
import org.http4k.format.ConfigurableKotlinxSerialization
import org.http4k.format.JsonType
import org.http4k.format.JsonType.Array
import org.http4k.format.JsonType.Boolean
import org.http4k.format.JsonType.Integer
import org.http4k.format.JsonType.Number
import org.http4k.format.JsonType.String
import kotlin.reflect.KClass

/**
 * Creates an [ApiRenderer] suitable for use with [org.http4k.contract.openapi.v3.OpenApi3] when
 * using an [AutoMarshallingJson] that does not support reflective serialization of http4k's
 * internal OpenAPI spec model (e.g. kotlinx.serialization).
 *
 * - Spec structure is serialized via [OpenApi3ApiRenderer], which builds JSON manually without
 *   reflection.
 * - Request/response schemas are derived from [AutoMarshallingJson.asJsonObject] for field types,
 *   with schema naming and required-field detection using the kotlinx.serialization
 *   [SerialDescriptor].
 * - Sealed interfaces/classes in [schemaHints] are rendered as `oneOf` + `discriminator` schemas.
 *
 * @param schemaHints Maps [ResponseMeta.definitionId] names to their Kotlin classes, enabling
 *   correct schema naming and required-field detection for response bodies. This is needed because
 *   http4k passes a pre-serialized JSON node (not the original Kotlin object) to [toSchema] for
 *   response bodies, losing type information. Sealed types in this map produce `oneOf` schemas.
 * @param serializersModule Used to resolve serializers for [schemaHints] classes via contextual
 *   lookup, with [serializerOrNull] as a fallback for classes not in the module.
 * @param classDiscriminator The JSON field name used to distinguish sealed subclasses. Defaults to
 *   `"type"`. Should match the value configured in the [kotlinx.serialization.json.Json] instance.
 */
fun <NODE : Any> openApiRenderer(
    json: AutoMarshallingJson<NODE>,
    schemaHints: Map<String, KClass<*>> = emptyMap(),
    serializersModule: SerializersModule = EmptySerializersModule(),
    classDiscriminator: String = "type",
): ApiRenderer<Api<NODE>, NODE> = OpenApiSchemaRenderer(json, schemaHints, serializersModule, classDiscriminator)

/**
 * Overload of [openApiRenderer] that accepts an [OpenApiSchemas] instance instead of a raw
 * `schemaHints` map. The schema hints are derived from [OpenApiSchemas.schemaHints].
 *
 * Parameter schemas registered in [schemas] via [asParameterMetadata] on their lenses are
 * automatically discovered from the rendered spec and injected into `components/schemas`.
 *
 * @param parameterSchemas Additional [OpenApiSchemas] whose types should appear as named schemas in
 *   `components/schemas` but do NOT need contextual serializer registration. Intended for query
 *   parameter types (enums, value classes) that must not be included in [schemas] to avoid
 *   circular class-initialization dependencies between the serialization module and handler code.
 */
fun openApiRenderer(
    json: ConfigurableKotlinxSerialization,
    schemas: OpenApiSchemas = OpenApiSchemas(),
    parameterSchemas: OpenApiSchemas = OpenApiSchemas(),
): ApiRenderer<Api<JsonElement>, JsonElement> =
    openApiRenderer(
        json = json,
        schemaHints = schemas.schemaHints + parameterSchemas.schemaHints,
        serializersModule =
            SerializersModule {
                include(json.json.serializersModule)
                include(schemas.serializersModule())
                include(parameterSchemas.serializersModule())
            },
        classDiscriminator = json.json.configuration.classDiscriminator,
    )

private class OpenApiSchemaRenderer<NODE : Any>(
    private val json: AutoMarshallingJson<NODE>,
    private val schemaHints: Map<String, KClass<*>>,
    private val serializersModule: SerializersModule,
    private val classDiscriminator: String,
) : ApiRenderer<Api<NODE>, NODE> {
    private val specRenderer = OpenApi3ApiRenderer(json)
    private val jsonToJsonSchema = JsonToJsonSchema(json)

    // Maps KClass → preferred schema name, derived from schemaHints (which maps name → KClass).
    private val preferredNameByClass: Map<KClass<*>, String> = schemaHints.entries.associate { (name, kClass) -> kClass to name }

    // Maps kotlinx.serialization serialName → preferred schema name.
    // Allows sealed subclass naming without kotlin-reflect by keying on serialName.
    // A subclass with @SerialName("simple") registered as "SimpleCollection" produces:
    //   "fest.ly…SimpleCollectionResponse" → "SimpleCollection"
    // NOTE: this map has conflicts when multiple sealed classes share the same @SerialName value
    // (e.g., StringFieldDefinition and StringFieldInput both use @SerialName("string")).
    // Prefer preferredNameByDescriptor for subclass resolution.
    private val preferredNameBySerialName: Map<String, String> =
        buildMap {
            for ((kClass, preferredName) in preferredNameByClass) {
                descriptorFor(kClass)?.serialName?.let { put(it, preferredName) }
            }
        }

    // Maps descriptor instance → preferred schema name. Unlike preferredNameBySerialName, this
    // correctly handles sealed subclasses from different sealed parents that share the same
    // @SerialName discriminator value (e.g., StringFieldDefinition and StringFieldInput both
    // use @SerialName("string") but must produce distinct schema names).
    // Requires the class's descriptor to be registered in schemaHints.
    private val preferredNameByDescriptor: Map<SerialDescriptor, String> =
        preferredNameByClass.entries
            .mapNotNull { (kClass, name) -> descriptorFor(kClass)?.let { it to name } }
            .toMap()

    override fun api(api: Api<NODE>): NODE = specRenderer.api(api).stripNulls().injectComponentParameters()

    override fun toSchema(
        obj: Any,
        overrideDefinitionId: String?,
        refModelNamePrefix: String?,
    ): JsonSchema<NODE> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            try {
                val node = obj as NODE
                if (json.typeOf(node) == JsonType.Object && overrideDefinitionId != null) {
                    // Response body: http4k passes a pre-serialized JSON node.
                    val definitionId = refModelNamePrefix.orEmpty() + overrideDefinitionId
                    objectSchema(definitionId, node, schemaHints[overrideDefinitionId], refModelNamePrefix.orEmpty())
                } else {
                    jsonToJsonSchema.toSchema(node, overrideDefinitionId, refModelNamePrefix)
                }
            } catch (_: ClassCastException) {
                // Request body: http4k passes the raw Kotlin object.
                val jsonNode = json.asJsonObject(obj)
                val kClass = overrideDefinitionId?.let { schemaHints[it] } ?: obj::class
                val definitionId =
                    refModelNamePrefix.orEmpty() +
                        (
                            overrideDefinitionId
                                ?: preferredNameByClass[kClass]
                                ?: obj::class.simpleName
                                ?: "object${jsonNode.hashCode()}"
                        )
                objectSchema(definitionId, jsonNode, kClass, refModelNamePrefix.orEmpty())
            }
        }.getOrElse { JsonSchema(json.obj(), emptyMap()) }

    // Dispatches to sealedSchema for sealed types or buildObjectSchema for regular objects.
    private fun objectSchema(
        definitionId: String,
        jsonNode: NODE,
        kClass: KClass<*>?,
        refModelNamePrefix: String,
    ): JsonSchema<NODE> {
        val descriptor = kClass?.let { descriptorFor(it) }
        return if (descriptor?.kind == SEALED) {
            sealedSchema(descriptor, definitionId, refModelNamePrefix)
        } else {
            buildObjectSchema(definitionId, jsonNode, descriptor, refModelNamePrefix)
        }
    }

    // Builds a named $ref + object definition, using the descriptor as the authoritative
    // field source so that optional/nullable fields absent from the JSON example are included.
    private fun buildObjectSchema(
        definitionId: String,
        jsonNode: NODE,
        descriptor: SerialDescriptor?,
        refModelNamePrefix: String,
    ): JsonSchema<NODE> {
        val properties = mutableListOf<Pair<String, NODE>>()
        val subDefinitions = mutableMapOf<String, NODE>()
        val requiredFields = mutableListOf<String>()

        if (descriptor != null) {
            val jsonFields = json.fields(jsonNode).associate { (k, v) -> k to v }
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val elementDescriptor = descriptor.getElementDescriptor(i)
                if (!descriptor.isElementOptional(i) && !elementDescriptor.isNullable) requiredFields += name
                val jsonValue = jsonFields[name]?.takeIf { json.typeOf(it) != JsonType.Null }
                val s =
                    if (jsonValue != null) {
                        fieldSchema(jsonValue, elementDescriptor, refModelNamePrefix)
                    } else {
                        val (schema, defs) = propertySchemaFromDescriptor(elementDescriptor, refModelNamePrefix)
                        JsonSchema(schema, defs)
                    }
                subDefinitions += s.definitions
                properties += name to applyConstraints(s.node, elementDescriptor.kind, descriptor.getElementAnnotations(i))
            }
        } else {
            for ((name, value) in json.fields(jsonNode)) {
                if (json.typeOf(value) == JsonType.Null) continue
                val s = fieldSchema(value, null, refModelNamePrefix)
                subDefinitions += s.definitions
                properties += name to s.node
            }
        }

        return JsonSchema(
            refTo(definitionId),
            subDefinitions + (definitionId to objectSchemaNode(requiredFields, properties)),
        )
    }

    // Resolves the schema for a JSON field value. Structured types (array/object) use the
    // descriptor for accurate typing; primitives use jsonToJsonSchema to preserve example values.
    // Enum types are routed through the descriptor path regardless of JSON value type so they
    // produce named $ref schemas rather than inline example values.
    private fun fieldSchema(
        value: NODE,
        descriptor: SerialDescriptor?,
        refModelNamePrefix: String,
    ): JsonSchema<NODE> {
        if (descriptor != null && descriptor.kind == ENUM) {
            val (schema, defs) = propertySchemaFromDescriptor(descriptor, refModelNamePrefix)
            return JsonSchema(schema, defs)
        }
        val isStructured = json.typeOf(value).let { it == Array || it == JsonType.Object }
        return if (descriptor != null && isStructured) {
            runCatching {
                val (schema, defs) = propertySchemaFromDescriptor(descriptor, refModelNamePrefix)
                JsonSchema(schema, defs)
            }.getOrElse { JsonSchema(typeSchemaFallback(value), emptyMap()) }
        } else {
            runCatching { jsonToJsonSchema.toSchema(value, null, refModelNamePrefix) }
                .getOrElse {
                    descriptor?.let {
                        val (schema, defs) = propertySchemaFromDescriptor(it, refModelNamePrefix)
                        JsonSchema(schema, defs)
                    } ?: JsonSchema(typeSchemaFallback(value), emptyMap())
                }
        }
    }

    // Builds a oneOf + discriminator schema for a sealed type.
    private fun sealedSchema(
        descriptor: SerialDescriptor,
        definitionId: String,
        refModelNamePrefix: String,
    ): JsonSchema<NODE> {
        // SEALED descriptor structure (kotlinx.serialization):
        //   element 0 — discriminator field descriptor (String)
        //   element 1 — "value": composite listing each subclass
        //     getElementName(i)       = @SerialName discriminator value (e.g. "detail")
        //     getElementDescriptor(i) = concrete subclass descriptor
        val subclassesDescriptor = descriptor.getElementDescriptor(1)

        val allDefinitions = mutableMapOf<String, NODE>()
        val subRefs = mutableListOf<NODE>()
        val mappingPairs = mutableListOf<Pair<String, NODE>>()

        for (i in 0 until subclassesDescriptor.elementsCount) {
            val discriminatorValue = subclassesDescriptor.getElementName(i)
            val subDescriptor = subclassesDescriptor.getElementDescriptor(i)
            val schemaName =
                preferredNameByDescriptor[subDescriptor]
                    ?: preferredNameBySerialName[subDescriptor.serialName]
                    ?: subDescriptor.serialName.substringAfterLast(".")
            val subDefinitionId = refModelNamePrefix + schemaName

            val (subSchemaNode, subDefs) = buildClassSchema(subDescriptor, refModelNamePrefix, discriminatorValue)
            allDefinitions += subDefs
            allDefinitions[subDefinitionId] = subSchemaNode
            subRefs += refTo(subDefinitionId)
            mappingPairs += discriminatorValue to json { string("#/components/schemas/$subDefinitionId") }
        }

        allDefinitions[definitionId] =
            json {
                obj(
                    "oneOf" to array(subRefs),
                    "discriminator" to
                        obj(
                            "propertyName" to string(classDiscriminator),
                            "mapping" to obj(mappingPairs),
                        ),
                )
            }

        return JsonSchema(refTo(definitionId), allDefinitions)
    }

    // Builds an object schema node from a SerialDescriptor. When discriminatorValue is provided
    // (sealed subclass case), a constant-enum discriminator property is prepended.
    private fun buildClassSchema(
        descriptor: SerialDescriptor,
        refModelNamePrefix: String,
        discriminatorValue: String? = null,
    ): Pair<NODE, Map<String, NODE>> {
        val subDefs = mutableMapOf<String, NODE>()
        val properties = mutableListOf<Pair<String, NODE>>()
        val requiredFields = mutableListOf<String>()

        if (discriminatorValue != null) {
            requiredFields += classDiscriminator
            properties += classDiscriminator to
                json {
                    obj("type" to string("string"), "enum" to array(listOf(string(discriminatorValue))))
                }
        }

        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val elementDescriptor = descriptor.getElementDescriptor(i)
            if (!descriptor.isElementOptional(i) && !elementDescriptor.isNullable) requiredFields += name
            val (propSchema, propDefs) = propertySchemaFromDescriptor(elementDescriptor, refModelNamePrefix)
            subDefs += propDefs
            properties += name to applyConstraints(propSchema, elementDescriptor.kind, descriptor.getElementAnnotations(i))
        }

        return objectSchemaNode(requiredFields, properties) to subDefs
    }

    private fun propertySchemaFromDescriptor(
        descriptor: SerialDescriptor,
        refModelNamePrefix: String,
    ): Pair<NODE, Map<String, NODE>> {
        val subDefs = mutableMapOf<String, NODE>()
        val schema =
            when (descriptor.kind) {
                STRING, CHAR -> {
                    json { obj("type" to string("string")) }
                }

                BOOLEAN -> {
                    json { obj("type" to string("boolean")) }
                }

                INT, LONG, SHORT, BYTE -> {
                    json { obj("type" to string("integer")) }
                }

                FLOAT, DOUBLE -> {
                    json { obj("type" to string("number")) }
                }

                ENUM -> {
                    val values = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                    val enumName = refModelNamePrefix + schemaNameFor(descriptor)
                    subDefs[enumName] = json { obj("type" to string("string"), "enum" to array(values.map { string(it) })) }
                    refTo(enumName)
                }

                LIST -> {
                    val (itemSchema, itemDefs) = propertySchemaFromDescriptor(descriptor.getElementDescriptor(0), refModelNamePrefix)
                    subDefs += itemDefs
                    json { obj("type" to string("array"), "items" to itemSchema) }
                }

                SEALED -> {
                    val sealedName = refModelNamePrefix + schemaNameFor(descriptor)
                    val sealed = sealedSchema(descriptor, sealedName, refModelNamePrefix)
                    subDefs += sealed.definitions
                    sealed.node
                }

                CLASS -> {
                    if (descriptor.isInline && descriptor.elementsCount == 1) {
                        val (inner, innerDefs) = propertySchemaFromDescriptor(descriptor.getElementDescriptor(0), refModelNamePrefix)
                        subDefs += innerDefs
                        inner
                    } else {
                        val className = refModelNamePrefix + schemaNameFor(descriptor)
                        val (classSchema, classDefs) = buildClassSchema(descriptor, refModelNamePrefix)
                        subDefs += classDefs
                        subDefs[className] = classSchema
                        refTo(className)
                    }
                }

                else -> {
                    json { obj("type" to string("object")) }
                }
            }
        return schema to subDefs
    }

    // Resolves the preferred schema name for a descriptor. Prefers the serialName-based lookup
    // (no reflection), falls back to Class.forName + preferredNameByClass, then to the simple
    // class name extracted from the fully-qualified serialName.
    private fun schemaNameFor(descriptor: SerialDescriptor): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        return preferredNameBySerialName[serialName]
            ?: runCatching { Class.forName(serialName).kotlin }.getOrNull()?.let { preferredNameByClass[it] }
            ?: serialName.substringAfterLast(".")
    }

    private fun objectSchemaNode(
        requiredFields: List<String>,
        properties: List<Pair<String, NODE>>,
    ): NODE =
        json {
            obj(
                listOfNotNull(
                    "type" to string("object"),
                    requiredFields.takeIf { it.isNotEmpty() }?.let { "required" to array(it.map { s -> string(s) }) },
                    "properties" to obj(properties),
                ),
            )
        }

    // Scans all path operation parameters for schemas containing a $ref (injected via
    // asParameterMetadata()), strips the spurious fields that http4k merges alongside it, and
    // injects the named schema definitions into components/schemas using schemaHints.
    private fun NODE.injectComponentParameters(): NODE {
        val specFields = json.fields(this).toMutableList()
        val discoveredSchemaNames = mutableSetOf<String>()

        val pathsIdx = specFields.indexOfFirst { (k, _) -> k == "paths" }
        if (pathsIdx >= 0) {
            val newPaths =
                json.fields(specFields[pathsIdx].second).map { (path, pathItem) ->
                    path to transformPathItem(pathItem, discoveredSchemaNames)
                }
            specFields[pathsIdx] = "paths" to json { obj(newPaths) }
        }

        if (discoveredSchemaNames.isNotEmpty()) {
            val componentsIdx = specFields.indexOfFirst { (k, _) -> k == "components" }
            if (componentsIdx >= 0) {
                val componentsFields = json.fields(specFields[componentsIdx].second).toMutableList()
                injectParameterSchemas(componentsFields, discoveredSchemaNames)
                specFields[componentsIdx] = "components" to json { obj(componentsFields) }
            }
        }

        return json { obj(specFields) }
    }

    private fun injectParameterSchemas(
        componentsFields: MutableList<Pair<String, NODE>>,
        schemaNames: Set<String>,
    ) {
        val schemasIdx = componentsFields.indexOfFirst { (k, _) -> k == "schemas" }
        if (schemasIdx >= 0) {
            val schemaFields = json.fields(componentsFields[schemasIdx].second).toMutableList()
            schemaNames.forEach { name ->
                val descriptor = schemaHints[name]?.let { descriptorFor(it) } ?: return@forEach
                schemaFields += name to schemaNodeForDescriptor(descriptor)
            }
            componentsFields[schemasIdx] = "schemas" to json { obj(schemaFields) }
        }
    }

    private fun schemaNodeForDescriptor(descriptor: SerialDescriptor): NODE =
        when (descriptor.kind) {
            STRING, CHAR -> {
                json { obj("type" to string("string")) }
            }

            BOOLEAN -> {
                json { obj("type" to string("boolean")) }
            }

            INT, LONG, SHORT, BYTE -> {
                json { obj("type" to string("integer")) }
            }

            FLOAT, DOUBLE -> {
                json { obj("type" to string("number")) }
            }

            ENUM -> {
                val values = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                json { obj("type" to string("string"), "enum" to array(values.map { string(it) })) }
            }

            CLASS -> {
                if (descriptor.isInline && descriptor.elementsCount == 1) {
                    schemaNodeForDescriptor(descriptor.getElementDescriptor(0))
                } else {
                    json { obj("type" to string("object")) }
                }
            }

            else -> {
                json { obj("type" to string("object")) }
            }
        }

    private fun transformPathItem(
        pathItem: NODE,
        discoveredSchemaNames: MutableSet<String>,
    ): NODE {
        val fields =
            json.fields(pathItem).map { (key, value) ->
                if (key in HTTP_METHODS) key to transformOperation(value, discoveredSchemaNames) else key to value
            }
        return json { obj(fields) }
    }

    private fun transformOperation(
        operation: NODE,
        discoveredSchemaNames: MutableSet<String>,
    ): NODE {
        val opFields = json.fields(operation).toMutableList()
        val paramsIdx = opFields.indexOfFirst { (k, _) -> k == "parameters" }
        if (paramsIdx < 0) return operation

        val newParams =
            json.elements(opFields[paramsIdx].second).map { param ->
                val schemaNode = json.fields(param).associate { it }["schema"] ?: return@map param
                val refNode = json.fields(schemaNode).associate { it }["\$ref"] ?: return@map param
                val refPath = runCatching { json.text(refNode) }.getOrNull() ?: return@map param
                discoveredSchemaNames += refPath.substringAfterLast("/")
                json {
                    obj(
                        json.fields(param).map { (k, v) ->
                            if (k == "schema") k to obj($$"$ref" to string(refPath)) else k to v
                        },
                    )
                }
            }

        opFields[paramsIdx] = "parameters" to json { array(newParams) }
        return json { obj(opFields) }
    }

    // Recursively removes null-valued fields from an OpenAPI spec JSON node.
    // OpenAPI 3.0 does not allow null for optional fields — they must be omitted.
    // OpenApi3ApiRenderer builds JSON manually and does not honour explicitNulls = false.
    private fun NODE.stripNulls(): NODE =
        when (json.typeOf(this)) {
            JsonType.Object -> {
                val fields =
                    json
                        .fields(this)
                        .filter { (_, v) -> json.typeOf(v) != JsonType.Null }
                        .map { (k, v) -> k to v.stripNulls() }
                json { obj(fields) }
            }

            Array -> {
                val elements = json.elements(this).map { it.stripNulls() }
                json { array(elements) }
            }

            else -> {
                this
            }
        }

    private fun refTo(definitionId: String): NODE = json { obj($$"$ref" to string("#/components/schemas/$definitionId")) }

    private fun typeSchemaFallback(value: NODE): NODE =
        json {
            obj(
                "type" to
                    string(
                        when (json.typeOf(value)) {
                            Array -> "array"
                            String -> "string"
                            Integer -> "integer"
                            Number -> "number"
                            Boolean -> "boolean"
                            else -> "object"
                        },
                    ),
            )
        }

    private fun applyConstraints(
        schema: NODE,
        kind: SerialKind,
        annotations: List<Annotation>,
    ): NODE {
        val extra = mutableListOf<Pair<String, NODE>>()
        for (annotation in annotations) {
            when (annotation) {
                is Format if kind in Format.applicableTo -> extra += "format" to json { string(annotation.value) }
                is MinLength if kind in MinLength.applicableTo -> extra += "minLength" to json { number(annotation.value) }
                is MaxLength if kind in MaxLength.applicableTo -> extra += "maxLength" to json { number(annotation.value) }
                is Pattern if kind in Pattern.applicableTo -> extra += "pattern" to json { string(annotation.value) }
                is Minimum if kind in Minimum.applicableTo -> extra += "minimum" to json { number(annotation.value) }
                is Maximum if kind in Maximum.applicableTo -> extra += "maximum" to json { number(annotation.value) }
                is MinItems if kind in MinItems.applicableTo -> extra += "minItems" to json { number(annotation.value) }
                is MaxItems if kind in MaxItems.applicableTo -> extra += "maxItems" to json { number(annotation.value) }
            }
        }
        if (extra.isEmpty()) return schema
        return json { obj(json.fields(schema) + extra) }
    }

    private fun descriptorFor(kClass: KClass<*>): SerialDescriptor? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (serializersModule.getContextual(kClass as KClass<Any>) ?: serializerOrNull(kClass.java))?.descriptor
        }.getOrNull()

    private companion object {
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
    }
}
