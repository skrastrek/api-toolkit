package io.skrastrek.api.toolkit.http4k.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.skrastrek.aws.lambda.kotlin.events.ApiGatewayProxyV1Event
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
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
import io.skrastrek.aws.lambda.kotlin.core.json as awsLambdaKotinCoreJson

/**
 * GraalVM native image requires compile-time generated serializers via @Serializable — no reflection.
 * Extends awsLambdaJson (explicitNulls=false, ignoreUnknownKeys=true) with:
 *   coerceInputValues=true  — API Gateway sends null for absent maps (e.g. queryStringParameters);
 *                             coercion maps null → emptyMap() for non-nullable fields with defaults.
 *   contextual serializer   — registers the compile-time ApiGatewayProxyV1Event serializer
 *                             explicitly so the native binary can find it without reflection.
 */
private val json =
    Json(awsLambdaKotinCoreJson) {
        serializersModule =
            SerializersModule {
                contextual(ApiGatewayProxyV1Event::class, ApiGatewayProxyV1Event.serializer())
            }
    }

open class ApiGatewayRestSseLambdaFunction(
    private val sseHandler: SseHandler,
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

        val sseResponse =
            runCatching {
                sseHandler(request)
            }.getOrElse { e ->
                context.logger.log("Unhandled exception: ${e.stackTraceToString()}")
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
}

@OptIn(ExperimentalSerializationApi::class)
internal fun InputStream.toApiGatewayProxyV1Event(): ApiGatewayProxyV1Event = json.decodeFromStream(this)

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
