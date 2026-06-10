package io.skrastrek.api.http4k.errors

import io.skrastrek.api.model.Error
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.UNAUTHORIZED
import org.http4k.core.Status.Companion.UNSUPPORTED_MEDIA_TYPE
import org.http4k.lens.LensFailure
import org.http4k.sse.SseFilter
import org.http4k.sse.SseResponse

private val json =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

@Serializable
data class ErrorResponse(
    val message: String,
)

fun Error.Companion.responseFilter(onError: (error: Error) -> Unit): Filter =
    Filter { next ->
        {
            try {
                next(it)
            } catch (ex: Throwable) {
                ex.toError().also(onError).toResponse()
            }
        }
    }

fun Error.Companion.sseResponseFilter(onError: (error: Error) -> Unit): SseFilter =
    SseFilter { next ->
        { request ->
            try {
                next(request)
            } catch (ex: Throwable) {
                SseResponse(
                    ex
                        .toError()
                        .also(onError)
                        .toResponse()
                        .status,
                ) {}
            }
        }
    }

fun Throwable.toError(): Error =
    when (this) {
        is Error -> this
        is LensFailure -> Error.InvalidRequest(cause?.message.orEmpty(), cause)
        else -> Error.Unexpected(this)
    }

fun Error.toResponse(): Response {
    val (status, message) =
        when (this) {
            is Error.InvalidRequest -> BAD_REQUEST to this.message
            is Error.Unauthorized -> UNAUTHORIZED to UNAUTHORIZED.description
            is Error.InsufficientAccess -> FORBIDDEN to this.message
            is Error.NotFound -> NOT_FOUND to this.message
            is Error.Unexpected -> INTERNAL_SERVER_ERROR to INTERNAL_SERVER_ERROR.description
            is Error.UnsupportedMediaType -> UNSUPPORTED_MEDIA_TYPE to this.message
        }
    return errorBody(status, message)
}

internal fun errorBody(
    status: Status,
    message: String,
): Response =
    Response(status)
        .header("content-type", ContentType.APPLICATION_JSON.toHeaderValue())
        .body(json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)))
