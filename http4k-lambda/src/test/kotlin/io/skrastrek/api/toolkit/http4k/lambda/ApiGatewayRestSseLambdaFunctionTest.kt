package io.skrastrek.api.toolkit.http4k.lambda

import kotlinx.coroutines.test.runTest
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

class ApiGatewayRestSseLambdaFunctionTest {
    @Test
    fun `suspending handle streams prelude and sse messages`() =
        runTest {
            val function =
                ApiGatewayRestSseLambdaFunction {
                    SseResponse(Status.OK, listOf("X-Custom" to "yes")) { sse ->
                        sse.send(SseMessage.Data("hello"))
                        sse.send(SseMessage.Event("update", "world"))
                    }
                }

            val output = ByteArrayOutputStream()
            function.handle(apiGatewayProxyV1EventJson().byteInputStream(), output, TestContext())

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":200")
            assertContains(prelude, "\"Content-Type\":\"text/event-stream\"")
            assertContains(prelude, "\"Cache-Control\":\"no-cache, no-store\"")
            assertContains(prelude, "\"X-Custom\":\"yes\"")
            assertEquals("data: hello\n\nevent: update\ndata: world\n\n", payload)
        }

    @Test
    fun `aws handleRequest bridge produces the same bytes as suspending handle`() =
        runTest {
            fun function() =
                ApiGatewayRestSseLambdaFunction {
                    SseResponse { sse -> sse.send(SseMessage.Data("bridged")) }
                }

            val viaSuspend = ByteArrayOutputStream()
            function().handle(apiGatewayProxyV1EventJson().byteInputStream(), viaSuspend, TestContext())

            val viaBlocking = ByteArrayOutputStream()
            function().handleRequest(apiGatewayProxyV1EventJson().byteInputStream(), viaBlocking, TestContext())

            assertContentEquals(viaSuspend.toByteArray(), viaBlocking.toByteArray())
        }

    @Test
    fun `handler body runs off the calling thread`() =
        runTest {
            lateinit var handlerThread: String
            val function =
                ApiGatewayRestSseLambdaFunction {
                    handlerThread = Thread.currentThread().name
                    SseResponse { sse -> sse.send(SseMessage.Data("x")) }
                }

            val callerThread = Thread.currentThread().name
            function.handle(apiGatewayProxyV1EventJson().byteInputStream(), ByteArrayOutputStream(), TestContext())

            // The blocking SSE consumer loop must not occupy the caller's dispatcher thread.
            assertNotEquals(callerThread, handlerThread)
        }

    @Test
    fun `request is translated with query parameters and headers`() =
        runTest {
            lateinit var seenUri: String
            lateinit var seenAccept: List<String?>
            val function =
                ApiGatewayRestSseLambdaFunction { request ->
                    seenUri = request.uri.toString()
                    seenAccept = request.headerValues("Accept")
                    SseResponse { }
                }

            function.handle(
                apiGatewayProxyV1EventJson(
                    path = "/events",
                    headers = mapOf("Accept" to listOf("text/event-stream")),
                    queryStringParameters = mapOf("since" to listOf("42")),
                ).byteInputStream(),
                ByteArrayOutputStream(),
                TestContext(),
            )

            assertEquals("/events?since=42", seenUri)
            assertEquals(listOf("text/event-stream"), seenAccept)
        }

    @Test
    fun `unparseable input writes a 500 prelude and logs`() =
        runTest {
            val function = ApiGatewayRestSseLambdaFunction { SseResponse { } }
            val output = ByteArrayOutputStream()
            val context = TestContext()

            function.handle("not json".byteInputStream(), output, context)

            val (prelude, payload) = output.toByteArray().splitOnPrelude()
            assertContains(prelude, "\"statusCode\":500")
            assertEquals("", payload)
            assertTrue(context.logged.any { it.startsWith("Could not parse request:") })
        }

    @Test
    fun `handler exception writes a 500 prelude and logs`() =
        runTest {
            val function = ApiGatewayRestSseLambdaFunction { error("boom") }
            val output = ByteArrayOutputStream()
            val context = TestContext()

            function.handle(apiGatewayProxyV1EventJson().byteInputStream(), output, context)

            assertContains(output.toByteArray().splitOnPrelude().first, "\"statusCode\":500")
            assertTrue(context.logged.any { it.startsWith("Unhandled exception:") })
        }

    @Test
    fun `close handlers fire once the consumer returns`() =
        runTest {
            var closed = false
            val function =
                ApiGatewayRestSseLambdaFunction {
                    SseResponse { sse -> sse.onClose { closed = true } }
                }

            function.handle(apiGatewayProxyV1EventJson().byteInputStream(), ByteArrayOutputStream(), TestContext())

            assertTrue(closed)
        }
}
