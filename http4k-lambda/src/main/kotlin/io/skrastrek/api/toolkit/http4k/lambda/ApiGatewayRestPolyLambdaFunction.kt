package io.skrastrek.api.toolkit.http4k.lambda

import com.amazonaws.services.lambda.runtime.Context
import io.skrastrek.aws.lambda.kotlin.coroutines.SuspendingRequestStreamHandler
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Serializers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.http4k.core.HttpHandler
import org.http4k.core.PolyHandler
import org.http4k.core.Request
import org.http4k.sse.PushAdaptingSse
import org.http4k.sse.Sse
import org.http4k.sse.SseHandler
import org.http4k.sse.SseMessage
import java.io.InputStream
import java.io.OutputStream

/**
 * Lambda streaming handler for http4k MCP PolyHandlers (result of `mcp()`).
 *
 * Routes to the SSE path when the client includes `text/event-stream` in its Accept header (streaming
 * Streamable-HTTP transport), and falls back to the HTTP path otherwise (non-streaming, synchronous
 * JSON-RPC response). This matches how http4k's poly() routing works in non-Lambda server configurations.
 */
open class ApiGatewayRestPolyLambdaFunction(
    private val polyHandler: PolyHandler,
) : SuspendingRequestStreamHandler,
    ApiGatewayProxyV1Serializers {
    /**
     * The body is confined to [IO] in full: parsing, both http4k handler calls, and every write to
     * [output] block, and on the SSE path the consumer loop lives as long as the client stays
     * subscribed. Running that directly on the caller's dispatcher would pin one of its threads for
     * the whole invocation.
     *
     * On the AWS managed Java runtime the inherited `handleRequest` bridges here via `runBlocking`;
     * a custom/native runtime calls this method directly and never pays for that bridge. The
     * private helpers below stay blocking on purpose — they only ever run inside this [IO] block.
     */
    override suspend fun handle(
        input: InputStream,
        output: OutputStream,
        context: Context,
    ) {
        withContext(IO) {
            val request =
                runCatching {
                    decodeEvent(input).toHttp4kRequest()
                }.getOrElse { e ->
                    context.logger.log("Could not parse request: ${e.stackTraceToString()}")
                    output.writePrelude(500, emptyMap())
                    output.flush()
                    return@withContext
                }

            val acceptsEventStream =
                request
                    .headerValues("Accept")
                    .flatMap { (it ?: "").split(",") }
                    .any { it.trim().startsWith("text/event-stream") }

            if (acceptsEventStream) {
                val sseHandler = polyHandler.sse
                if (sseHandler != null) {
                    handleSse(request, output, context, sseHandler)
                    return@withContext
                }
            }

            val httpHandler = polyHandler.http
            if (httpHandler != null) {
                handleHttp(request, output, context, httpHandler)
            } else {
                output.writePrelude(404, emptyMap())
                output.flush()
            }
        }
    }

    private fun handleSse(
        request: Request,
        outputStream: OutputStream,
        context: Context,
        sseHandler: SseHandler,
    ) {
        val sseResponse =
            runCatching {
                sseHandler(request)
            }.getOrElse { e ->
                context.logger.log("Unhandled exception in SSE handler: ${e.stackTraceToString()}")
                outputStream.writePrelude(500, emptyMap())
                outputStream.flush()
                return
            }

        outputStream.writePrelude(
            sseResponse.status.code,
            buildMap {
                put("Content-Type", "text/event-stream")
                put("Cache-Control", "no-cache, no-store")
                sseResponse.headers.forEach { (k, v) -> if (v != null) put(k, v) }
            },
        )
        outputStream.flush()

        val sse =
            object : PushAdaptingSse(request) {
                override fun send(message: SseMessage): Sse {
                    outputStream.write(message.toMessage().toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    return this
                }
            }

        try {
            sseResponse(sse)
        } finally {
            sse.triggerClose()
            outputStream.flush()
        }
    }

    private fun handleHttp(
        request: Request,
        outputStream: OutputStream,
        context: Context,
        httpHandler: HttpHandler,
    ) {
        val httpResponse =
            runCatching {
                httpHandler(request)
            }.getOrElse { e ->
                context.logger.log("Unhandled exception in HTTP handler: ${e.stackTraceToString()}")
                outputStream.writePrelude(500, emptyMap())
                outputStream.flush()
                return
            }

        outputStream.writePrelude(
            httpResponse.status.code,
            buildMap {
                httpResponse.headers.forEach { (k, v) -> if (v != null) put(k, v) }
            },
        )
        outputStream.flush()
        httpResponse.body.stream.use { it.copyTo(outputStream) }
        outputStream.flush()
    }
}
