package io.skrastrek.api.toolkit.http4k.lambda

import io.skrastrek.aws.lambda.kotlin.core.defaultJson
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Event
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.http4k.core.PolyHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.sse.SseHandler
import org.http4k.sse.SseResponse
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Decoding does not consult the [Json.serializersModule], which is why the private `Json` that used
 * to live in `ApiGatewayRestSseLambdaFunction` — registering a contextual serializer for
 * [ApiGatewayProxyV1Event] — could be deleted.
 *
 * That registration was inert even before the deletion. `SerializersModule.serializer(KType)`
 * resolves the compiled serializer first and only falls through to `getContextual` when a type has
 * none (kotlinx-serialization-core `Serializers.kt`, "slow path to find contextual serializers").
 * [ApiGatewayProxyV1Event] is a `@Serializable` data class, so its generated serializer always won
 * and the contextual entry was never reached — including on the reified `decodeFromStream<T>` path
 * the old code used. [compiled serializer takes precedence over a contextual registration] pins
 * that precedence directly.
 *
 * The handlers now decode through `decodeFromStream(deserializer, stream)`, which takes the strategy
 * as a parameter instead of resolving one at runtime. That is the only one of the three arrangements
 * that genuinely keeps serializer lookup off the reflective path a GraalVM native image cannot
 * follow; the contextual registration only ever appeared to.
 */
class SerializersModuleIndependenceTest {
    private object PoisonedEventSerializer : KSerializer<ApiGatewayProxyV1Event> {
        override val descriptor = ApiGatewayProxyV1Event.serializer().descriptor

        override fun serialize(
            encoder: Encoder,
            value: ApiGatewayProxyV1Event,
        ) = error("serializers module was consulted")

        override fun deserialize(decoder: Decoder): ApiGatewayProxyV1Event = error("serializers module was consulted")
    }

    private val poisonedJson =
        Json(defaultJson) {
            serializersModule =
                SerializersModule {
                    contextual(PoisonedEventSerializer)
                }
        }

    private class PoisonedSseFunction(
        sseHandler: SseHandler,
        override val json: Json,
    ) : ApiGatewayRestSseLambdaFunction(sseHandler)

    private class PoisonedPolyFunction(
        polyHandler: PolyHandler,
        override val json: Json,
    ) : ApiGatewayRestPolyLambdaFunction(polyHandler)

    @Test
    fun `sse function decodes without consulting the serializers module`() =
        runTest {
            lateinit var seenUri: String
            val output = ByteArrayOutputStream()

            PoisonedSseFunction({ request ->
                seenUri = request.uri.toString()
                SseResponse { }
            }, poisonedJson)
                .handle(
                    apiGatewayProxyV1EventJson(
                        path = "/events",
                        queryStringParameters = mapOf("since" to listOf("42")),
                    ).byteInputStream(),
                    output,
                    TestContext(),
                )

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":200")
            assertEquals("/events?since=42", seenUri)
        }

    @Test
    fun `poly function decodes without consulting the serializers module`() =
        runTest {
            val output = ByteArrayOutputStream()

            PoisonedPolyFunction(PolyHandler(http = { Response(Status.OK).body("ok") }), poisonedJson)
                .handle(apiGatewayProxyV1EventJson().byteInputStream(), output, TestContext())

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":200")
            assertEquals("ok", payload)
        }

    /**
     * Documents why the deleted registration was dead code: even the reified path, which does route
     * through `serializersModule.serializer()`, resolves the generated serializer and never reaches
     * the poisoned contextual entry. Had the entry ever been consulted, this would throw.
     */
    @Test
    fun `compiled serializer takes precedence over a contextual registration`() {
        val failure =
            runCatching {
                poisonedJson.decodeFromString<ApiGatewayProxyV1Event>(apiGatewayProxyV1EventJson())
            }.exceptionOrNull()

        assertNull(failure, "expected the generated serializer to win over the contextual entry")
    }
}
