package io.skrastrek.api.http4k

import io.skrastrek.api.http4k.openapi.OpenApiSchema
import io.skrastrek.api.http4k.openapi.OpenApiSchemas
import io.skrastrek.api.http4k.openapi.openApiRenderer
import io.skrastrek.api.openapi.annotations.Format
import io.skrastrek.api.openapi.annotations.MaxItems
import io.skrastrek.api.openapi.annotations.MaxLength
import io.skrastrek.api.openapi.annotations.Maximum
import io.skrastrek.api.openapi.annotations.MinItems
import io.skrastrek.api.openapi.annotations.MinLength
import io.skrastrek.api.openapi.annotations.Minimum
import io.skrastrek.api.openapi.annotations.Pattern
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.http4k.contract.Tag
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.ApiPath
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.contract.openapi.v3.Components
import org.http4k.contract.openapi.v3.RequestParameter
import org.http4k.core.Uri
import org.http4k.format.ConfigurableKotlinxSerialization
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// --- Test model ---

@Serializable
enum class WidgetCategory { STANDARD, PREMIUM, CUSTOM }

@Serializable
sealed interface WidgetResponse {
    val id: String
}

@Serializable
@SerialName("basic")
data class BasicWidgetResponse(
    override val id: String,
    val name: String,
    val count: Int,
    val category: WidgetCategory? = null,
) : WidgetResponse

@Serializable
@SerialName("featured")
data class FeaturedWidgetResponse(
    override val id: String,
    val name: String,
    val price: Double,
    val features: List<String>,
) : WidgetResponse

@Serializable
data class ItemResponse(
    val id: String,
    val name: String,
    val quantity: Int,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CreateItemRequest(
    val name: String,
    val quantity: Int,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

// TagResponse is a sealed type whose subclasses must be registered in schemaHints so that
// the renderer can resolve their preferred schema names without kotlin-reflect.
// Matches the production pattern: sealed subclasses with @SerialName must be registered.
@Serializable
sealed interface TagResponse {
    val text: String
}

@Serializable
@SerialName("plain_tag")
data class PlainTagResponse(
    override val text: String,
) : TagResponse

@Serializable
data class ContainerResponse(
    val id: String,
    val tags: List<TagResponse>,
)

@Serializable
data class AuthorResponse(
    val name: String,
    val bio: String? = null,
)

@Serializable
data class ArticleResponse(
    val id: String,
    val title: String,
    val author: AuthorResponse,
)

@Serializable
data class CreateArticleRequest(
    val title: String,
    val author: AuthorResponse,
)

// LabelResponse is in schemaHints (has a preferred name), but only appears as a list item
// inside FolderResponse — it is never the top-level response body for any route.
// This reproduces the production pattern where TagGroup / ReactionResponse / VoteResponse
// are referenced only as list items inside their container responses.
@Serializable
data class LabelResponse(
    val name: String,
    val value: String,
)

@Serializable
data class FolderResponse(
    val id: String,
    val labels: List<LabelResponse>,
)

// ShapeResponse is a sealed type whose subclasses have @SerialName values that differ
// from the desired schema names. Subclasses are registered in schemaHints with preferred
// names ("CircleShape", "RectShape"), so serialNameToPreferredName must resolve them correctly
// instead of falling back to the raw @SerialName ("circle", "rect").
// Reproduces the production pattern: SimpleCollectionResponse (@SerialName("simple"))
// should appear as "SimpleCollection", not "simple".
@Serializable
sealed interface ShapeResponse

@Serializable
@SerialName("circle")
data class CircleShapeResponse(
    val radius: Double,
) : ShapeResponse

@Serializable
@SerialName("rect")
data class RectShapeResponse(
    val width: Double,
    val height: Double,
) : ShapeResponse

// AddressResponse is a nested type that appears as a nullable field on ProfileResponse.
// When the example omits address (null), it must still appear in the schema — reproducing
// the production pattern for EventResponse.theme and ActivityResponse.registration.
@Serializable
data class AddressResponse(
    val street: String,
    val city: String,
)

@Serializable
data class ProfileResponse(
    val id: String,
    val name: String,
    val address: AddressResponse? = null,
    val score: Int? = null,
)

@Serializable
data class ConstrainedRequest(
    @MinLength(2) val code: String,
    @MaxLength(50) val label: String,
    @Pattern("[A-Z]{3}") val isoCode: String,
    @Format("email") val email: String,
    @Minimum(1.0) val count: Int,
    @Maximum(999.0) val score: Double,
    @MinItems(1) val requiredItems: List<String>,
    @MaxItems(5) val optionalTags: List<String> = emptyList(),
)

// ConstraintMismatchRequest has @MinLength on an Int property — the renderer must silently
// ignore constraints that are inapplicable to the property's SerialKind.
@Serializable
data class ConstraintMismatchRequest(
    @MinLength(5) val value: Int,
)

@Serializable
sealed interface ConstrainedShape

@Serializable
@SerialName("constrained_square")
data class SquareShape(
    @MinLength(1) val name: String,
    @Minimum(0.1) val side: Double,
) : ConstrainedShape

// SortOrder is intentionally NOT in testSchemas — it is a parameter type, registered only via
// parameterSchemas so it never ends up in the SerializersModule.
@Serializable
enum class SortOrder { ASC, DESC }

@Serializable
data class SortedItemResponse(
    val id: String,
    val sortOrder: SortOrder,
)

// --- Test schemas ---

private val testSchemas =
    OpenApiSchemas(
        OpenApiSchema("Widget", WidgetResponse::class, WidgetResponse.serializer()),
        OpenApiSchema("BasicWidgetResponse", BasicWidgetResponse::class, BasicWidgetResponse.serializer()),
        OpenApiSchema("FeaturedWidgetResponse", FeaturedWidgetResponse::class, FeaturedWidgetResponse.serializer()),
        OpenApiSchema("Item", ItemResponse::class, ItemResponse.serializer()),
        OpenApiSchema("CreateItem", CreateItemRequest::class, CreateItemRequest.serializer()),
        OpenApiSchema("Container", ContainerResponse::class, ContainerResponse.serializer()),
        OpenApiSchema("TagResponse", TagResponse::class, TagResponse.serializer()),
        OpenApiSchema("PlainTagResponse", PlainTagResponse::class, PlainTagResponse.serializer()),
        OpenApiSchema("Article", ArticleResponse::class, ArticleResponse.serializer()),
        OpenApiSchema("CreateArticle", CreateArticleRequest::class, CreateArticleRequest.serializer()),
        OpenApiSchema("Folder", FolderResponse::class, FolderResponse.serializer()),
        OpenApiSchema("Label", LabelResponse::class, LabelResponse.serializer()),
        OpenApiSchema("Shape", ShapeResponse::class, ShapeResponse.serializer()),
        OpenApiSchema("CircleShape", CircleShapeResponse::class, CircleShapeResponse.serializer()),
        OpenApiSchema("RectShape", RectShapeResponse::class, RectShapeResponse.serializer()),
        OpenApiSchema("Address", AddressResponse::class, AddressResponse.serializer()),
        OpenApiSchema("Profile", ProfileResponse::class, ProfileResponse.serializer()),
        OpenApiSchema("ConstrainedRequest", ConstrainedRequest::class, ConstrainedRequest.serializer()),
        OpenApiSchema("ConstraintMismatch", ConstraintMismatchRequest::class, ConstraintMismatchRequest.serializer()),
        OpenApiSchema("ConstrainedShape", ConstrainedShape::class, ConstrainedShape.serializer()),
        OpenApiSchema("SquareShape", SquareShape::class, SquareShape.serializer()),
        OpenApiSchema("SortedItem", SortedItemResponse::class, SortedItemResponse.serializer()),
    )

// --- Test serialization ---

private object TestSerialization : ConfigurableKotlinxSerialization({
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    serializersModule = testSchemas.serializersModule()
})

class OpenApiRendererTest {
    private val renderer = openApiRenderer(TestSerialization, schemas = testSchemas)

    // Renderer with SortOrder registered as a parameterSchema under the preferred name
    // "SortDirection" — distinct from the class simple name "SortOrder" to make it clear that
    // the preferred name comes from parameterSchemas, not the class name fallback.
    private val paramSchemaRenderer =
        openApiRenderer(
            TestSerialization,
            schemas = testSchemas,
            parameterSchemas =
                OpenApiSchemas(
                    OpenApiSchema("SortDirection", SortOrder::class, SortOrder.serializer()),
                ),
        )

    private fun itemNode() = TestSerialization.asJsonObject(ItemResponse(id = "i1", name = "Item", quantity = 3, tags = listOf("example")))

    private fun widgetNode() = TestSerialization.asJsonObject(BasicWidgetResponse(id = "w1", name = "Widget", count = 1))

    private fun featuredExample() = FeaturedWidgetResponse(id = "w2", name = "Featured", price = 9.99, features = listOf("fast"))

    // --- Schema naming ---

    @Test
    fun `schema definition uses overrideDefinitionId as map key`() {
        val schema = renderer.toSchema(itemNode(), "Item", "")
        assertContains(schema.definitions.keys, "Item")
    }

    @Test
    fun `schema ref node points to overrideDefinitionId`() {
        val schema = renderer.toSchema(itemNode(), "Item", "")
        assertEquals(
            "#/components/schemas/Item",
            schema.node.jsonObject[$$"$ref"]!!
                .jsonPrimitive.content,
        )
    }

    // --- Required fields for regular classes ---

    @Test
    fun `required fields include non-nullable non-optional properties`() {
        val schema = renderer.toSchema(itemNode(), "Item", "")
        val required =
            schema.definitions["Item"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertContains(required, "id")
        assertContains(required, "name")
        assertContains(required, "quantity")
    }

    @Test
    fun `required fields exclude nullable properties`() {
        val schema = renderer.toSchema(itemNode(), "Item", "")
        val required =
            schema.definitions["Item"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("description" in required)
    }

    @Test
    fun `required fields exclude properties with default values`() {
        val schema = renderer.toSchema(itemNode(), "Item", "")
        val required =
            schema.definitions["Item"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("tags" in required)
    }

    // --- Sealed type: overall schema structure ---

    @Test
    fun `sealed type generates oneOf schema`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val widgetDef = schema.definitions["Widget"]!!.jsonObject
        assertContains(widgetDef.keys, "oneOf")
        assertEquals(2, widgetDef["oneOf"]!!.jsonArray.size)
    }

    @Test
    fun `sealed type does not generate type object schema`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val widgetDef = schema.definitions["Widget"]!!.jsonObject
        assertFalse("type" in widgetDef)
        assertFalse("properties" in widgetDef)
    }

    @Test
    fun `sealed type includes discriminator with configured property name`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val discriminator = schema.definitions["Widget"]!!.jsonObject["discriminator"]!!.jsonObject
        assertEquals("type", discriminator["propertyName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed type discriminator mapping contains all subclass serial names`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val mapping =
            schema.definitions["Widget"]!!
                .jsonObject["discriminator"]!!
                .jsonObject["mapping"]!!
                .jsonObject
        assertContains(mapping.keys, "basic")
        assertContains(mapping.keys, "featured")
    }

    @Test
    fun `sealed type discriminator mapping values reference subclass schemas by class name`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val mapping =
            schema.definitions["Widget"]!!
                .jsonObject["discriminator"]!!
                .jsonObject["mapping"]!!
                .jsonObject
        assertEquals("#/components/schemas/BasicWidgetResponse", mapping["basic"]!!.jsonPrimitive.content)
        assertEquals("#/components/schemas/FeaturedWidgetResponse", mapping["featured"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed type definitions include all subclass schemas keyed by class name`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        assertContains(schema.definitions.keys, "BasicWidgetResponse")
        assertContains(schema.definitions.keys, "FeaturedWidgetResponse")
    }

    // --- Sealed subclass schemas: required fields ---

    @Test
    fun `sealed subclass required fields include discriminator`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val required =
            schema.definitions["BasicWidgetResponse"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertContains(required, "type")
    }

    @Test
    fun `sealed subclass required fields include non-nullable non-optional properties`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val required =
            schema.definitions["BasicWidgetResponse"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertContains(required, "id")
        assertContains(required, "name")
        assertContains(required, "count")
    }

    @Test
    fun `sealed subclass required fields exclude nullable optional properties`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val required =
            schema.definitions["BasicWidgetResponse"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("category" in required)
    }

    // --- Sealed subclass schemas: discriminator property ---

    @Test
    fun `sealed subclass discriminator property is a constant string enum`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val typeProperty =
            schema.definitions["BasicWidgetResponse"]!!
                .jsonObject["properties"]!!
                .jsonObject["type"]!!
                .jsonObject
        assertEquals("string", typeProperty["type"]!!.jsonPrimitive.content)
        assertEquals(1, typeProperty["enum"]!!.jsonArray.size)
        assertEquals(
            "basic",
            typeProperty["enum"]!!
                .jsonArray
                .first()
                .jsonPrimitive.content,
        )
    }

    // --- Sealed subclass schemas: property types ---

    @Test
    fun `sealed subclass string property generates string schema`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val properties = schema.definitions["BasicWidgetResponse"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("string", properties["id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", properties["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed subclass integer property generates integer schema`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val properties = schema.definitions["BasicWidgetResponse"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("integer", properties["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed subclass double property generates number schema`() {
        val featuredNode = TestSerialization.asJsonObject(featuredExample())
        val schema = renderer.toSchema(featuredNode, "Widget", "")
        val properties = schema.definitions["FeaturedWidgetResponse"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("number", properties["price"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed subclass list property generates array schema with items`() {
        val featuredNode = TestSerialization.asJsonObject(featuredExample())
        val schema = renderer.toSchema(featuredNode, "Widget", "")
        val featuresProperty =
            schema.definitions["FeaturedWidgetResponse"]!!
                .jsonObject["properties"]!!
                .jsonObject["features"]!!
                .jsonObject
        assertEquals("array", featuresProperty["type"]!!.jsonPrimitive.content)
        assertContains(featuresProperty.keys, "items")
    }

    @Test
    fun `sealed subclass enum property generates ref to named enum schema`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        val categoryProperty =
            schema.definitions["BasicWidgetResponse"]!!
                .jsonObject["properties"]!!
                .jsonObject["category"]!!
                .jsonObject
        assertEquals("#/components/schemas/WidgetCategory", categoryProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed subclass enum property produces named enum definition with all values`() {
        val schema = renderer.toSchema(widgetNode(), "Widget", "")
        assertContains(schema.definitions.keys, "WidgetCategory")
        val enumDef = schema.definitions["WidgetCategory"]!!.jsonObject
        assertEquals("string", enumDef["type"]!!.jsonPrimitive.content)
        val enumValues = enumDef["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(enumValues, "STANDARD")
        assertContains(enumValues, "PREMIUM")
        assertContains(enumValues, "CUSTOM")
    }

    // --- Request body: JSON node path (http4k serializes example before calling toSchema) ---

    @Test
    fun `request body node path schema definition uses overrideDefinitionId`() {
        val node = TestSerialization.asJsonObject(CreateItemRequest(name = "Widget", quantity = 5))
        val schema = renderer.toSchema(node, "CreateItem", "")
        assertContains(schema.definitions.keys, "CreateItem")
    }

    @Test
    fun `request body node path required fields include non-nullable non-optional properties`() {
        val node = TestSerialization.asJsonObject(CreateItemRequest(name = "Widget", quantity = 5))
        val schema = renderer.toSchema(node, "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertContains(required, "name")
        assertContains(required, "quantity")
    }

    @Test
    fun `request body node path required fields exclude nullable properties`() {
        val node = TestSerialization.asJsonObject(CreateItemRequest(name = "Widget", quantity = 5))
        val schema = renderer.toSchema(node, "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("description" in required)
    }

    @Test
    fun `request body node path required fields exclude properties with default values`() {
        val node = TestSerialization.asJsonObject(CreateItemRequest(name = "Widget", quantity = 5))
        val schema = renderer.toSchema(node, "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("tags" in required)
    }

    // --- Request body: Kotlin object path (when http4k passes the raw object to toSchema) ---

    @Test
    fun `request body object path schema definition uses overrideDefinitionId`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        assertContains(schema.definitions.keys, "CreateItem")
    }

    @Test
    fun `request body object path schema ref points to overrideDefinitionId`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        assertEquals(
            "#/components/schemas/CreateItem",
            schema.node.jsonObject[$$"$ref"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `request body object path required fields include non-nullable non-optional properties`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertContains(required, "name")
        assertContains(required, "quantity")
    }

    @Test
    fun `request body object path required fields exclude nullable properties`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("description" in required)
    }

    @Test
    fun `request body object path required fields exclude properties with default values`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        val required =
            schema.definitions["CreateItem"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("tags" in required)
    }

    // --- Array fields with empty list examples ---

    @Test
    fun `array field with empty list example includes items from descriptor - node path`() {
        val node = TestSerialization.asJsonObject(CreateItemRequest(name = "Widget", quantity = 5))
        val schema = renderer.toSchema(node, "CreateItem", "")
        val tagsProperty =
            schema.definitions["CreateItem"]!!
                .jsonObject["properties"]!!
                .jsonObject["tags"]!!
                .jsonObject
        assertEquals("array", tagsProperty["type"]!!.jsonPrimitive.content)
        assertContains(tagsProperty.keys, "items")
        assertEquals("string", tagsProperty["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `array field with empty list example includes items from descriptor - object path`() {
        val schema = renderer.toSchema(CreateItemRequest(name = "Widget", quantity = 5), "CreateItem", "")
        val tagsProperty =
            schema.definitions["CreateItem"]!!
                .jsonObject["properties"]!!
                .jsonObject["tags"]!!
                .jsonObject
        assertEquals("array", tagsProperty["type"]!!.jsonPrimitive.content)
        assertContains(tagsProperty.keys, "items")
        assertEquals("string", tagsProperty["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // --- Nested sealed type in list field ---

    @Test
    fun `list field with sealed item type produces array schema with ref items`() {
        val node =
            TestSerialization.asJsonObject(
                ContainerResponse(id = "c1", tags = listOf(PlainTagResponse(text = "hello"))),
            )
        val schema = renderer.toSchema(node, "Container", "")
        val tagsProperty =
            schema.definitions["Container"]!!
                .jsonObject["properties"]!!
                .jsonObject["tags"]!!
                .jsonObject
        assertEquals("array", tagsProperty["type"]!!.jsonPrimitive.content)
        assertContains(tagsProperty.keys, "items")
        assertEquals(
            "#/components/schemas/TagResponse",
            tagsProperty["items"]!!.jsonObject[$$"$ref"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `list field with sealed item type includes sealed type definition in schema`() {
        val node =
            TestSerialization.asJsonObject(
                ContainerResponse(id = "c1", tags = listOf(PlainTagResponse(text = "hello"))),
            )
        val schema = renderer.toSchema(node, "Container", "")
        assertContains(schema.definitions.keys, "TagResponse")
        assertContains(schema.definitions["TagResponse"]!!.jsonObject.keys, "oneOf")
    }

    @Test
    fun `list field with sealed item type includes sealed subclass definitions`() {
        val node =
            TestSerialization.asJsonObject(
                ContainerResponse(id = "c1", tags = listOf(PlainTagResponse(text = "hello"))),
            )
        val schema = renderer.toSchema(node, "Container", "")
        assertContains(schema.definitions.keys, "PlainTagResponse")
    }

    // --- Nested object fields ---

    @Test
    fun `nested object field generates named ref schema instead of hash-based name - node path`() {
        val node = TestSerialization.asJsonObject(ArticleResponse(id = "a1", title = "Title", author = AuthorResponse(name = "Alice")))
        val schema = renderer.toSchema(node, "Article", "")
        val authorProperty =
            schema.definitions["Article"]!!
                .jsonObject["properties"]!!
                .jsonObject["author"]!!
                .jsonObject
        assertEquals("#/components/schemas/AuthorResponse", authorProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested object field produces named definition - node path`() {
        val node = TestSerialization.asJsonObject(ArticleResponse(id = "a1", title = "Title", author = AuthorResponse(name = "Alice")))
        val schema = renderer.toSchema(node, "Article", "")
        assertContains(schema.definitions.keys, "AuthorResponse")
        assertFalse(schema.definitions.keys.any { it.startsWith("object") })
    }

    @Test
    fun `nested object field generates named ref schema instead of hash-based name - object path`() {
        val schema =
            renderer.toSchema(
                CreateArticleRequest(title = "Title", author = AuthorResponse(name = "Alice")),
                "CreateArticle",
                "",
            )
        val authorProperty =
            schema.definitions["CreateArticle"]!!
                .jsonObject["properties"]!!
                .jsonObject["author"]!!
                .jsonObject
        assertEquals("#/components/schemas/AuthorResponse", authorProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested object field produces named definition - object path`() {
        val schema =
            renderer.toSchema(
                CreateArticleRequest(title = "Title", author = AuthorResponse(name = "Alice")),
                "CreateArticle",
                "",
            )
        assertContains(schema.definitions.keys, "AuthorResponse")
        assertFalse(schema.definitions.keys.any { it.startsWith("object") })
    }

    @Test
    fun `empty list field with sealed item type still includes sealed type definition`() {
        val node = TestSerialization.asJsonObject(ContainerResponse(id = "c1", tags = emptyList()))
        val schema = renderer.toSchema(node, "Container", "")
        val tagsProperty =
            schema.definitions["Container"]!!
                .jsonObject["properties"]!!
                .jsonObject["tags"]!!
                .jsonObject
        assertEquals("array", tagsProperty["type"]!!.jsonPrimitive.content)
        assertContains(tagsProperty.keys, "items")
        assertEquals(
            "#/components/schemas/TagResponse",
            tagsProperty["items"]!!.jsonObject[$$"$ref"]!!.jsonPrimitive.content,
        )
        assertContains(schema.definitions.keys, "TagResponse")
    }

    // --- Named (schemaHints) class appearing only as a list item ---
    //
    // Reproduces the production pattern where TagGroup / ReactionResponse / VoteResponse
    // are registered in schemaHints but never appear as a top-level response body — only
    // as items inside a container response. Previously the renderer emitted a $ref to the
    // preferred name but never built the corresponding definition, leaving the spec invalid.

    @Test
    fun `list item type in schemaHints produces array schema with preferred name ref`() {
        val node = TestSerialization.asJsonObject(FolderResponse(id = "f1", labels = listOf(LabelResponse(name = "env", value = "prod"))))
        val schema = renderer.toSchema(node, "Folder", "")
        val labelsProperty =
            schema.definitions["Folder"]!!
                .jsonObject["properties"]!!
                .jsonObject["labels"]!!
                .jsonObject
        assertEquals("array", labelsProperty["type"]!!.jsonPrimitive.content)
        assertContains(labelsProperty.keys, "items")
        assertEquals(
            "#/components/schemas/Label",
            labelsProperty["items"]!!.jsonObject[$$"$ref"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `list item type in schemaHints includes definition under preferred name`() {
        val node = TestSerialization.asJsonObject(FolderResponse(id = "f1", labels = listOf(LabelResponse(name = "env", value = "prod"))))
        val schema = renderer.toSchema(node, "Folder", "")
        assertContains(schema.definitions.keys, "Label")
    }

    @Test
    fun `list item type in schemaHints definition includes correct properties`() {
        val node = TestSerialization.asJsonObject(FolderResponse(id = "f1", labels = listOf(LabelResponse(name = "env", value = "prod"))))
        val schema = renderer.toSchema(node, "Folder", "")
        val labelDef = schema.definitions["Label"]!!.jsonObject
        assertContains(labelDef["properties"]!!.jsonObject.keys, "name")
        assertContains(labelDef["properties"]!!.jsonObject.keys, "value")
    }

    @Test
    fun `list item type in schemaHints included with empty list example`() {
        val node = TestSerialization.asJsonObject(FolderResponse(id = "f1", labels = emptyList()))
        val schema = renderer.toSchema(node, "Folder", "")
        assertContains(schema.definitions.keys, "Label")
    }

    // --- Optional/nullable fields absent from the JSON example ---
    //
    // When an example object omits a nullable field (it serialises as absent because
    // explicitNulls=false), that field must still appear in the schema properties — both
    // for the response-body (node) path and the request-body (object) path.
    // Reproduces: EventResponse.theme and ActivityResponse.registration missing from spec.

    @Test
    fun `nullable object field absent from example is included in schema properties - node path`() {
        val node = TestSerialization.asJsonObject(ProfileResponse(id = "p1", name = "Alice"))
        val schema = renderer.toSchema(node, "Profile", "")
        val properties = schema.definitions["Profile"]!!.jsonObject["properties"]!!.jsonObject
        assertContains(properties.keys, "address")
    }

    @Test
    fun `nullable object field absent from example generates ref to nested type - node path`() {
        val node = TestSerialization.asJsonObject(ProfileResponse(id = "p1", name = "Alice"))
        val schema = renderer.toSchema(node, "Profile", "")
        val addressProperty =
            schema.definitions["Profile"]!!
                .jsonObject["properties"]!!
                .jsonObject["address"]!!
                .jsonObject
        assertEquals("#/components/schemas/Address", addressProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nullable object field absent from example produces nested type definition - node path`() {
        val node = TestSerialization.asJsonObject(ProfileResponse(id = "p1", name = "Alice"))
        val schema = renderer.toSchema(node, "Profile", "")
        assertContains(schema.definitions.keys, "Address")
    }

    @Test
    fun `nullable primitive field absent from example is included in schema properties - node path`() {
        val node = TestSerialization.asJsonObject(ProfileResponse(id = "p1", name = "Alice"))
        val schema = renderer.toSchema(node, "Profile", "")
        val properties = schema.definitions["Profile"]!!.jsonObject["properties"]!!.jsonObject
        assertContains(properties.keys, "score")
    }

    @Test
    fun `nullable fields absent from example are not in required - node path`() {
        val node = TestSerialization.asJsonObject(ProfileResponse(id = "p1", name = "Alice"))
        val schema = renderer.toSchema(node, "Profile", "")
        val required =
            schema.definitions["Profile"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("address" in required)
        assertFalse("score" in required)
    }

    @Test
    fun `nullable object field absent from example is included in schema properties - object path`() {
        val schema = renderer.toSchema(ProfileResponse(id = "p1", name = "Alice"), "Profile", "")
        val properties = schema.definitions["Profile"]!!.jsonObject["properties"]!!.jsonObject
        assertContains(properties.keys, "address")
    }

    @Test
    fun `nullable object field absent from example generates ref to nested type - object path`() {
        val schema = renderer.toSchema(ProfileResponse(id = "p1", name = "Alice"), "Profile", "")
        val addressProperty =
            schema.definitions["Profile"]!!
                .jsonObject["properties"]!!
                .jsonObject["address"]!!
                .jsonObject
        assertEquals("#/components/schemas/Address", addressProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nullable object field absent from example produces nested type definition - object path`() {
        val schema = renderer.toSchema(ProfileResponse(id = "p1", name = "Alice"), "Profile", "")
        assertContains(schema.definitions.keys, "Address")
    }

    @Test
    fun `nullable primitive field absent from example is included in schema properties - object path`() {
        val schema = renderer.toSchema(ProfileResponse(id = "p1", name = "Alice"), "Profile", "")
        val properties = schema.definitions["Profile"]!!.jsonObject["properties"]!!.jsonObject
        assertContains(properties.keys, "score")
    }

    @Test
    fun `nullable fields absent from example are not in required - object path`() {
        val schema = renderer.toSchema(ProfileResponse(id = "p1", name = "Alice"), "Profile", "")
        val required =
            schema.definitions["Profile"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertFalse("address" in required)
        assertFalse("score" in required)
    }

    // --- Sealed subclass naming when @SerialName differs from preferred schema name ---
    //
    // When a sealed subclass has @SerialName("circle") but is registered in schemaHints as
    // "CircleShape", the generated schema must use "CircleShape" — not the raw @SerialName.
    // Reproduces: SimpleCollectionResponse (@SerialName("simple")) → "SimpleCollection".

    private fun circleShapeNode() = TestSerialization.asJsonObject(CircleShapeResponse(radius = 5.0))

    @Test
    fun `sealed subclass with short SerialName uses preferred schema name from schemaHints`() {
        val schema = renderer.toSchema(circleShapeNode(), "Shape", "")
        assertContains(schema.definitions.keys, "CircleShape")
        assertFalse("circle" in schema.definitions.keys)
    }

    @Test
    fun `all sealed subclasses with short SerialNames use preferred schema names`() {
        val schema = renderer.toSchema(circleShapeNode(), "Shape", "")
        assertContains(schema.definitions.keys, "CircleShape")
        assertContains(schema.definitions.keys, "RectShape")
        assertFalse("circle" in schema.definitions.keys)
        assertFalse("rect" in schema.definitions.keys)
    }

    @Test
    fun `sealed discriminator mapping uses preferred schema names not SerialName values`() {
        val schema = renderer.toSchema(circleShapeNode(), "Shape", "")
        val mapping =
            schema.definitions["Shape"]!!
                .jsonObject["discriminator"]!!
                .jsonObject["mapping"]!!
                .jsonObject
        assertEquals("#/components/schemas/CircleShape", mapping["circle"]!!.jsonPrimitive.content)
        assertEquals("#/components/schemas/RectShape", mapping["rect"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sealed oneOf refs use preferred schema names not SerialName values`() {
        val schema = renderer.toSchema(circleShapeNode(), "Shape", "")
        val oneOf = schema.definitions["Shape"]!!.jsonObject["oneOf"]!!.jsonArray
        val refs = oneOf.map { it.jsonObject[$$"$ref"]!!.jsonPrimitive.content }
        assertContains(refs, "#/components/schemas/CircleShape")
        assertContains(refs, "#/components/schemas/RectShape")
    }

    // --- OpenAPI constraint annotations ---
    //
    // Verifies that @MinLength, @MaxLength, @Pattern, @Format, @Minimum, @Maximum, @MinItems,
    // and @MaxItems annotations on @Serializable properties are emitted as the corresponding
    // OpenAPI schema fields. Also verifies that a constraint is silently ignored when its
    // applicableTo kind does not match the property's SerialKind.

    private fun constrainedExample() =
        ConstrainedRequest(
            code = "ab",
            label = "some label",
            isoCode = "ABC",
            email = "a@b.com",
            count = 5,
            score = 50.0,
            requiredItems = listOf("item1"),
        )

    @Test
    fun `MinLength annotation on string property emits minLength in schema - object path`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["code"]!!
                .jsonObject
        assertEquals(2, prop["minLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `MinLength annotation retains base string type alongside constraint`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["code"]!!
                .jsonObject
        assertEquals("string", prop["type"]!!.jsonPrimitive.content)
        assertEquals(2, prop["minLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `MaxLength annotation on string property emits maxLength in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["label"]!!
                .jsonObject
        assertEquals(50, prop["maxLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `Pattern annotation on string property emits pattern in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["isoCode"]!!
                .jsonObject
        assertEquals("[A-Z]{3}", prop["pattern"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Format annotation on string property emits format in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["email"]!!
                .jsonObject
        assertEquals("email", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Minimum annotation on integer property emits minimum in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["count"]!!
                .jsonObject
        assertEquals(1.0, prop["minimum"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `Maximum annotation on number property emits maximum in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["score"]!!
                .jsonObject
        assertEquals(999.0, prop["maximum"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `MinItems annotation on list property emits minItems in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["requiredItems"]!!
                .jsonObject
        assertEquals(1, prop["minItems"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `MaxItems annotation on list property emits maxItems in schema`() {
        val schema = renderer.toSchema(constrainedExample(), "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["optionalTags"]!!
                .jsonObject
        assertEquals(5, prop["maxItems"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `MinLength annotation on string property emits minLength in schema - node path`() {
        val node = TestSerialization.asJsonObject(constrainedExample())
        val schema = renderer.toSchema(node, "ConstrainedRequest", "")
        val prop =
            schema.definitions["ConstrainedRequest"]!!
                .jsonObject["properties"]!!
                .jsonObject["code"]!!
                .jsonObject
        assertEquals(2, prop["minLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `constraint annotation inapplicable to property type is not emitted`() {
        val schema = renderer.toSchema(ConstraintMismatchRequest(value = 42), "ConstraintMismatch", "")
        val prop =
            schema.definitions["ConstraintMismatch"]!!
                .jsonObject["properties"]!!
                .jsonObject["value"]!!
                .jsonObject
        assertEquals("integer", prop["type"]!!.jsonPrimitive.content)
        assertFalse("minLength" in prop.keys)
    }

    @Test
    fun `constraint annotations on sealed subclass properties are emitted via buildClassSchema`() {
        val node = TestSerialization.asJsonObject(SquareShape(name = "s", side = 3.0))
        val schema = renderer.toSchema(node, "ConstrainedShape", "")
        val prop = schema.definitions["SquareShape"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(
            1,
            prop["name"]!!
                .jsonObject["minLength"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            0.1,
            prop["side"]!!
                .jsonObject["minimum"]!!
                .jsonPrimitive.content
                .toDouble(),
        )
    }

    // --- parameterSchemas ---
    //
    // Verifies that types registered in parameterSchemas contribute to schema naming and that
    // injectComponentParameters injects their definitions into components/schemas when they
    // appear as $ref-referenced parameter schemas in the rendered API spec.
    //
    // SortOrder is not in testSchemas (no entry in the SerializersModule). Its descriptor is
    // resolved via serializerOrNull() fallback inside descriptorFor().

    @Test
    fun `parameterSchemas type with preferred name is used when referenced as nested enum field`() {
        val schema =
            paramSchemaRenderer.toSchema(
                SortedItemResponse(id = "x", sortOrder = SortOrder.ASC),
                "SortedItem",
                "",
            )
        val sortProperty =
            schema.definitions["SortedItem"]!!
                .jsonObject["properties"]!!
                .jsonObject["sortOrder"]!!
                .jsonObject
        assertEquals("#/components/schemas/SortDirection", sortProperty[$$"$ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parameterSchemas type preferred name produces named schema definition`() {
        val schema =
            paramSchemaRenderer.toSchema(
                SortedItemResponse(id = "x", sortOrder = SortOrder.ASC),
                "SortedItem",
                "",
            )
        assertContains(schema.definitions.keys, "SortDirection")
        assertFalse("SortOrder" in schema.definitions.keys)
    }

    @Test
    fun `parameterSchemas enum definition has correct type and values`() {
        val schema =
            paramSchemaRenderer.toSchema(
                SortedItemResponse(id = "x", sortOrder = SortOrder.ASC),
                "SortedItem",
                "",
            )
        val def = schema.definitions["SortDirection"]!!.jsonObject
        assertEquals("string", def["type"]!!.jsonPrimitive.content)
        val enumValues = def["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(enumValues, "ASC")
        assertContains(enumValues, "DESC")
    }

    private fun sortParam(schema: JsonElement) =
        RequestParameter.PrimitiveParameter(
            Meta(
                required = false,
                location = "query",
                paramMeta = ParamMeta.StringParam,
                name = "sortOrder",
                description = null,
                metadata = emptyMap(),
            ),
            schema,
        )

    private fun minimalApi(parameters: List<RequestParameter<JsonElement>>): Api<JsonElement> {
        val emptySchemas: JsonElement = buildJsonObject {}
        return Api(
            info = ApiInfo("Test", "1.0"),
            tags = emptyList<Tag>(),
            paths =
                mapOf(
                    "/items" to
                        mapOf(
                            "get" to
                                ApiPath.NoBody(
                                    summary = "List items",
                                    description = null,
                                    tags = null,
                                    parameters = parameters,
                                    responses = emptyMap(),
                                    security = null,
                                    operationId = null,
                                    deprecated = null,
                                    callbacks = null,
                                ),
                        ),
                ),
            components = Components(emptySchemas, emptySchemas),
            servers = listOf(ApiServer(Uri.of("/"))),
            webhooks = null,
            openapi = "3.0.0",
        )
    }

    @Test
    fun `injectComponentParameters injects parameterSchemas enum definition into components schemas`() {
        val refSchema = buildJsonObject { put("\$ref", "#/components/schemas/SortDirection") }
        val result = paramSchemaRenderer.api(minimalApi(listOf(sortParam(refSchema))))
        val schemas = result.jsonObject["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertContains(schemas.keys, "SortDirection")
    }

    @Test
    fun `injectComponentParameters injected enum schema has correct type and values`() {
        val refSchema = buildJsonObject { put("\$ref", "#/components/schemas/SortDirection") }
        val result = paramSchemaRenderer.api(minimalApi(listOf(sortParam(refSchema))))
        val def =
            result.jsonObject["components"]!!
                .jsonObject["schemas"]!!
                .jsonObject["SortDirection"]!!
                .jsonObject
        assertEquals("string", def["type"]!!.jsonPrimitive.content)
        val enumValues = def["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(enumValues, "ASC")
        assertContains(enumValues, "DESC")
    }

    @Test
    fun `injectComponentParameters strips spurious fields alongside dollar-ref in parameter schema`() {
        val refSchemaWithExtras =
            buildJsonObject {
                put("\$ref", "#/components/schemas/SortDirection")
                put("type", "string")
            }
        val result = paramSchemaRenderer.api(minimalApi(listOf(sortParam(refSchemaWithExtras))))
        val paramSchema =
            result
                .jsonObject["paths"]!!
                .jsonObject["/items"]!!
                .jsonObject["get"]!!
                .jsonObject["parameters"]!!
                .jsonArray
                .first()
                .jsonObject["schema"]!!
                .jsonObject
        assertEquals("#/components/schemas/SortDirection", paramSchema[$$"$ref"]!!.jsonPrimitive.content)
        assertFalse("type" in paramSchema.keys)
    }
}
