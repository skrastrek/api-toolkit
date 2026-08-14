package io.skrastrek.api.toolkit.http4k.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import io.skrastrek.aws.lambda.kotlin.core.EmptyContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [EmptyContext.getLogger] returns null, and the lambda functions log on their error paths, so tests
 * that exercise those paths need a context that actually carries a logger.
 */
class TestContext : Context by EmptyContext {
    val logged = mutableListOf<String>()

    private val lambdaLogger =
        object : LambdaLogger {
            override fun log(message: String) {
                logged += message
            }

            override fun log(message: ByteArray) {
                logged += String(message, Charsets.UTF_8)
            }
        }

    override fun getLogger(): LambdaLogger = lambdaLogger
}

fun apiGatewayProxyV1EventJson(
    path: String = "/events",
    httpMethod: String = "GET",
    headers: Map<String, List<String>> = emptyMap(),
    queryStringParameters: Map<String, List<String>> = emptyMap(),
    body: String? = null,
    isBase64Encoded: Boolean = false,
): String =
    buildJsonObject {
        put("resource", path)
        put("path", path)
        put("httpMethod", httpMethod)
        put(
            "multiValueHeaders",
            buildJsonObject {
                headers.forEach { (k, vs) -> put(k, buildJsonArray { vs.forEach { add(it) } }) }
            },
        )
        put(
            "multiValueQueryStringParameters",
            buildJsonObject {
                queryStringParameters.forEach { (k, vs) -> put(k, buildJsonArray { vs.forEach { add(it) } }) }
            },
        )
        put("isBase64Encoded", isBase64Encoded)
        if (body != null) put("body", body)
        put(
            "requestContext",
            buildJsonObject {
                put("accountId", "123456789012")
                put("apiId", "api-id")
                put("domainName", "api-id.execute-api.eu-west-1.amazonaws.com")
                put("domainPrefix", "api-id")
                put("extendedRequestId", "extended-request-id")
                put("httpMethod", httpMethod)
                put("identity", buildJsonObject { })
                put("path", path)
                put("protocol", "HTTP/1.1")
                put("requestId", "request-id")
                put("requestTime", "14/Aug/2026:09:00:00 +0000")
                put("requestTimeEpoch", 1_776_157_200_000)
                put("resourcePath", path)
                put("stage", "prod")
            },
        )
    }.toString()

/**
 * The streaming wire format is the prelude JSON, then eight NUL bytes, then the payload.
 * Splits a captured stream back into those two halves.
 */
fun ByteArray.splitOnPrelude(): Pair<String, String> {
    val separator = (0..size - 8).firstOrNull { i -> (0 until 8).all { this[i + it] == 0.toByte() } }
    requireNotNull(separator) { "no 8-byte prelude separator found in $size bytes" }
    return String(copyOfRange(0, separator), Charsets.UTF_8) to
        String(copyOfRange(separator + 8, size), Charsets.UTF_8)
}
