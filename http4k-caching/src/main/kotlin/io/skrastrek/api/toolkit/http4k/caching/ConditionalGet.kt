package io.skrastrek.api.toolkit.http4k.caching

import io.skrastrek.api.toolkit.model.Modifiable
import io.skrastrek.api.toolkit.model.Versionable
import io.skrastrek.api.toolkit.model.md5
import org.http4k.core.Request
import org.http4k.core.Response
import kotlin.time.Instant

context(request: Request)
suspend fun <T : Versionable> T.ifNoneMatch(
    seed: String = "",
    noneMatch: suspend (ETag?) -> Response,
    match: (ETag) -> Response,
): Response =
    request
        .ifNoneMatch()
        ?.let { eTag ->
            if (eTag.value == md5(seed)) {
                match(eTag)
            } else {
                noneMatch(eTag)
            }
        } ?: noneMatch(null)

context(request: Request)
suspend fun <T : Versionable> List<T>.ifNoneMatch(
    seed: String = "",
    noneMatch: suspend (ETag?) -> Response,
    match: (ETag) -> Response,
): Response =
    request
        .ifNoneMatch()
        ?.let { eTag ->
            if (eTag.value == md5(seed)) {
                match(eTag)
            } else {
                noneMatch(eTag)
            }
        } ?: noneMatch(null)

context(request: Request)
suspend fun <T : Modifiable> T.ifModifiedSince(
    modifiedSince: suspend (Instant?) -> Response,
    notModifiedSince: (Instant) -> Response,
): Response =
    request
        .ifModifiedSince()
        ?.let { lastModified ->
            if (hasBeenModifiedSince(lastModified)) {
                modifiedSince(lastModified)
            } else {
                notModifiedSince(lastModified)
            }
        } ?: modifiedSince(null)

context(request: Request)
suspend fun <T : Modifiable> List<T>.ifAnyModifiedSince(
    modifiedSince: suspend (Instant?) -> Response,
    notModifiedSince: (Instant) -> Response,
): Response =
    request
        .ifModifiedSince()
        ?.let { lastModified ->
            if (any { it.hasBeenModifiedSince(lastModified) }) {
                modifiedSince(lastModified)
            } else {
                notModifiedSince(lastModified)
            }
        } ?: modifiedSince(null)
