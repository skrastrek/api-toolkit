package io.skrastrek.api.toolkit.http4k.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
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
) : RequestStreamHandler {
    override fun handleRequest(
        inputStream: InputStream,
        outputStream: OutputStream,
        context: Context,
    ) {
        val request =
            runCatching {
                inputStream.toApiGatewayProxyV1Event().toHttp4kRequest()
            }.getOrElse { e ->
                context.logger.log("Could not parse request: ${e.stackTraceToString()}")
                outputStream.writePrelude(500, emptyMap())
                outputStream.flush()
                return
            }

        val acceptsEventStream =
            request
                .headerValues("Accept")
                .flatMap { (it ?: "").split(",") }
                .any { it.trim().startsWith("text/event-stream") }

        if (acceptsEventStream) {
            val sseHandler = polyHandler.sse
            if (sseHandler != null) {
                handleSse(request, outputStream, context, sseHandler)
                return
            }
        }

        val httpHandler = polyHandler.http
        if (httpHandler != null) {
            handleHttp(request, outputStream, context, httpHandler)
        } else {
            outputStream.writePrelude(404, emptyMap())
            outputStream.flush()
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
