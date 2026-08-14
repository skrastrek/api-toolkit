package io.skrastrek.api.toolkit.http4k.lambda

import kotlinx.coroutines.test.runTest
import org.http4k.core.PolyHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.sse.SseMessage
import org.http4k.sse.SseResponse
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiGatewayRestPolyLambdaFunctionTest {
    private val jsonRpcBody = """{"jsonrpc":"2.0"}"""

    private fun poly(
        http: Boolean = true,
        sse: Boolean = true,
    ) = PolyHandler(
        http =
            if (http) {
                { _ -> Response(Status.OK).header("Content-Type", "application/json").body(jsonRpcBody) }
            } else {
                null
            },
        sse =
            if (sse) {
                { _ -> SseResponse { s -> s.send(SseMessage.Data("streamed")) } }
            } else {
                null
            },
    )

    @Test
    fun `accept event-stream routes to the sse handler`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestPolyLambdaFunction(poly()).handle(
                apiGatewayProxyV1EventJson(
                    headers = mapOf("Accept" to listOf("application/json, text/event-stream")),
                ).byteInputStream(),
                output,
                TestContext(),
            )

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"Content-Type\":\"text/event-stream\"")
            assertEquals("data: streamed\n\n", payload)
        }

    @Test
    fun `without event-stream accept routes to the http handler`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestPolyLambdaFunction(poly()).handle(
                apiGatewayProxyV1EventJson(
                    httpMethod = "POST",
                    headers = mapOf("Accept" to listOf("application/json")),
                    body = """{"jsonrpc":"2.0","method":"ping"}""",
                ).byteInputStream(),
                output,
                TestContext(),
            )

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":200")
            assertContains(prelude, "\"Content-Type\":\"application/json\"")
            assertEquals(jsonRpcBody, payload)
        }

    @Test
    fun `event-stream accept falls back to http when no sse handler is configured`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestPolyLambdaFunction(poly(sse = false)).handle(
                apiGatewayProxyV1EventJson(
                    headers = mapOf("Accept" to listOf("text/event-stream")),
                ).byteInputStream(),
                output,
                TestContext(),
            )

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":200")
            assertEquals(jsonRpcBody, payload)
        }

    @Test
    fun `no http handler yields 404`() =
        runTest {
            val output = ByteArrayOutputStream()

            ApiGatewayRestPolyLambdaFunction(poly(http = false, sse = false)).handle(
                apiGatewayProxyV1EventJson().byteInputStream(),
                output,
                TestContext(),
            )

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":404")
        }

    @Test
    fun `aws handleRequest bridge produces the same bytes as suspending handle`() =
        runTest {
            val event = apiGatewayProxyV1EventJson(headers = mapOf("Accept" to listOf("text/event-stream")))

            val viaSuspend = ByteArrayOutputStream()
            ApiGatewayRestPolyLambdaFunction(poly()).handle(event.byteInputStream(), viaSuspend, TestContext())

            val viaBlocking = ByteArrayOutputStream()
            ApiGatewayRestPolyLambdaFunction(poly()).handleRequest(event.byteInputStream(), viaBlocking, TestContext())

            assertContentEquals(viaSuspend.toByteArray(), viaBlocking.toByteArray())
        }

    @Test
    fun `handler body runs off the calling thread`() =
        runTest {
            lateinit var handlerThread: String
            val function =
                ApiGatewayRestPolyLambdaFunction(
                    PolyHandler(
                        http = { _ ->
                            handlerThread = Thread.currentThread().name
                            Response(Status.OK)
                        },
                    ),
                )

            val callerThread = Thread.currentThread().name
            function.handle(apiGatewayProxyV1EventJson().byteInputStream(), ByteArrayOutputStream(), TestContext())

            assertNotEquals(callerThread, handlerThread)
        }

    @Test
    fun `unparseable input writes a 500 prelude and logs`() =
        runTest {
            val output = ByteArrayOutputStream()
            val context = TestContext()

            ApiGatewayRestPolyLambdaFunction(poly()).handle("not json".byteInputStream(), output, context)

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Could not parse request:") })
        }

    @Test
    fun `http handler exception writes a 500 prelude and logs`() =
        runTest {
            val output = ByteArrayOutputStream()
            val context = TestContext()

            ApiGatewayRestPolyLambdaFunction(PolyHandler(http = { error("boom") }))
                .handle(apiGatewayProxyV1EventJson().byteInputStream(), output, context)

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Unhandled exception in HTTP handler:") })
        }

    @Test
    fun `sse handler exception writes a 500 prelude and logs`() =
        runTest {
            val output = ByteArrayOutputStream()
            val context = TestContext()

            ApiGatewayRestPolyLambdaFunction(PolyHandler(sse = { error("boom") }))
                .handle(
                    apiGatewayProxyV1EventJson(headers = mapOf("Accept" to listOf("text/event-stream")))
                        .byteInputStream(),
                    output,
                    context,
                )

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Unhandled exception in SSE handler:") })
        }
}
