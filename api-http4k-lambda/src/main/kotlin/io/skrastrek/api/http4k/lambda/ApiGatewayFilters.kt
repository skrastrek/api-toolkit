package io.skrastrek.api.http4k.lambda

import org.http4k.core.Filter
import org.http4k.sse.SseFilter

// REST API Gateway includes the base path mapping in the Lambda event path, unlike HTTP API Gateway.
// These filters strip the prefix before routing so routes and the OpenAPI spec stay prefix-free.

object ApiGatewayFilters {
    fun stripBasePath(prefix: String): Filter =
        { next ->
            { req -> next(req.uri(req.uri.path(req.uri.path.removePrefix(prefix).ifEmpty { "/" }))) }
        }
}

object ApiGatewaySseFilters {
    fun stripBasePath(prefix: String): SseFilter =
        { next ->
            { req -> next(req.uri(req.uri.path(req.uri.path.removePrefix(prefix).ifEmpty { "/" }))) }
        }
}
