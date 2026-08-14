package io.skrastrek.api.toolkit.http4k.lambda

import com.amazonaws.services.lambda.runtime.Context
import io.skrastrek.aws.lambda.kotlin.coroutines.SuspendingRequestStreamHandler
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Event
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Serializers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import org.http4k.core.Method
import org.http4k.core.Parameters
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.core.toUrlFormEncoded
import org.http4k.sse.PushAdaptingSse
import org.http4k.sse.Sse
import org.http4k.sse.SseHandler
import org.http4k.sse.SseMessage
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

open class ApiGatewayRestSseLambdaFunction(
    private val sseHandler: SseHandler,
) : SuspendingRequestStreamHandler,
    ApiGatewayProxyV1Serializers {
    /**
     * The body is confined to [IO] in full. Every step blocks — reading [input], the http4k
     * [SseHandler] call, and each write to [output] — and the consumer loop runs for as long as the
     * client stays subscribed, which for SSE is the point. Running that directly on the caller's
     * dispatcher would pin one of its threads for the whole connection.
     *
     * On the AWS managed Java runtime the inherited `handleRequest` bridges here via `runBlocking`;
     * a custom/native runtime calls this method directly and never pays for that bridge.
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

            val sseResponse =
                runCatching {
                    sseHandler(request)
                }.getOrElse { e ->
                    context.logger.log("Unhandled exception: ${e.stackTraceToString()}")
                    output.writePrelude(500, emptyMap())
                    output.flush()
                    return@withContext
                }

            output.writePrelude(
                sseResponse.status.code,
                buildMap {
                    put("Content-Type", "text/event-stream")
                    put("Cache-Control", "no-cache, no-store")
                    sseResponse.headers.forEach { (k, v) -> if (v != null) put(k, v) }
                },
            )
            output.flush()

            val sse =
                object : PushAdaptingSse(request) {
                    override fun send(message: SseMessage): Sse {
                        output.write(message.toMessage().toByteArray(Charsets.UTF_8))
                        output.flush()
                        return this
                    }
                }

            try {
                sseResponse(sse)
            } finally {
                sse.triggerClose()
                output.flush()
            }
        }
    }
}

/**
 * Decodes the invocation payload with the handler's own [ApiGatewayProxyV1Serializers.json], so a
 * subclass that overrides `json` changes how its own events are parsed.
 *
 * Passing [ApiGatewayProxyV1Serializers.deserializer] explicitly keeps serializer lookup off the
 * reflective path, which a GraalVM native image cannot follow without extra configuration. This is
 * what this file previously achieved by registering a contextual serializer in a private `Json`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun ApiGatewayProxyV1Serializers.decodeEvent(input: InputStream): ApiGatewayProxyV1Event =
    json.decodeFromStream(deserializer, input)

internal fun ApiGatewayProxyV1Event.toHttp4kRequest(): Request {
    val headers: Parameters =
        multiValueHeaders
            .ifEmpty { headers.mapValues { listOf(it.value) } }
            .flatMap { (k, vs) -> vs.map { k to it } }
    val queryParams: Parameters =
        multiValueQueryStringParameters
            .ifEmpty { queryStringParameters.mapValues { listOf(it.value) } }
            .flatMap { (k, vs) -> vs.map { k to it } }

    val bodyBytes =
        if (isBase64Encoded && body != null) {
            Base64.getDecoder().decode(body)
        } else {
            (body ?: "").toByteArray(Charsets.UTF_8)
        }

    val uri = if (queryParams.isEmpty()) Uri.of(path) else Uri.of(path).query(queryParams.toUrlFormEncoded())

    return Request(Method.valueOf(httpMethod), uri)
        .headers(headers)
        .body(bodyBytes.inputStream(), bodyBytes.size.toLong())
}

internal fun OutputStream.writePrelude(
    statusCode: Int,
    headers: Map<String, String>,
) {
    val preludeJson =
        buildJsonObject {
            put("statusCode", statusCode)
            put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
        }.toString()
    write(preludeJson.toByteArray(Charsets.UTF_8))
    write(ByteArray(8))
}
