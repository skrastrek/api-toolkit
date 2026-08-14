package io.skrastrek.api.toolkit.http4k.lambda

import io.skrastrek.aws.lambda.kotlin.core.defaultJson
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Event
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.http4k.core.PolyHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.sse.SseHandler
import org.http4k.sse.SseResponse
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `json` reaches these functions through [io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Serializers],
 * so a subclass can replace the decoding configuration. These tests pin that it is genuinely the
 * subclass's `json` doing the decoding, not the default.
 *
 * `ignoreUnknownKeys` is the lever under test because the difference is observable from outside:
 * the default configuration tolerates unrecognised fields, a strict one rejects the payload.
 */
class OverridableJsonConfigTest {
    private val strictJson = Json(defaultJson) { ignoreUnknownKeys = false }

    private class StrictSseFunction(
        sseHandler: SseHandler,
        override val json: Json,
    ) : ApiGatewayRestSseLambdaFunction(sseHandler)

    private class StrictPolyFunction(
        polyHandler: PolyHandler,
        override val json: Json,
    ) : ApiGatewayRestPolyLambdaFunction(polyHandler)

    private val eventWithUnknownField =
        apiGatewayProxyV1EventJson(unknownFields = mapOf("fieldAddedByAwsLater" to "surprise"))

    @Test
    fun `sse function defaults to tolerating unknown fields`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestSseLambdaFunction { SseResponse { } }
                .handle(eventWithUnknownField.byteInputStream(), output, TestContext())

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":200")
        }

    @Test
    fun `sse function honours a subclass json override`() =
        runTest {
            val output = ByteArrayOutputStream()
            val context = TestContext()

            StrictSseFunction({ SseResponse { } }, strictJson)
                .handle(eventWithUnknownField.byteInputStream(), output, context)

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Could not parse request:") })
        }

    @Test
    fun `poly function defaults to tolerating unknown fields`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestPolyLambdaFunction(PolyHandler(http = { Response(Status.OK).body("ok") }))
                .handle(eventWithUnknownField.byteInputStream(), output, TestContext())

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":200")
            assertEquals("ok", payload)
        }

    @Test
    fun `poly function honours a subclass json override`() =
        runTest {
            val output = ByteArrayOutputStream()
            val context = TestContext()

            StrictPolyFunction(PolyHandler(http = { Response(Status.OK).body("ok") }), strictJson)
                .handle(eventWithUnknownField.byteInputStream(), output, context)

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Could not parse request:") })
        }

    @Test
    fun `json defaults to the library default and deserializer is bound to the event type`() {
        val function = ApiGatewayRestSseLambdaFunction { SseResponse { } }

        assertEquals(defaultJson, function.json)
        assertEquals(ApiGatewayProxyV1Event.serializer().descriptor, function.deserializer.descriptor)
    }
}
