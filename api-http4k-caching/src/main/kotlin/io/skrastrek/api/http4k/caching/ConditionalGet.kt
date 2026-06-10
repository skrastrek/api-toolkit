package io.skrastrek.api.http4k.caching

import io.skrastrek.api.model.Modifiable
import io.skrastrek.api.model.Versionable
import io.skrastrek.api.model.md5
import org.http4k.core.Request
import org.http4k.core.Response
import kotlin.time.Instant

context(request: Request)
fun <T : Versionable> T.ifNoneMatch(
    noneMatch: (ETag?) -> Response,
    match: (ETag) -> Response,
): Response =
    request.ifNoneMatch()
        ?.let { eTag ->
            if (eTag.value == md5()) {
                match(eTag)
            } else {
                noneMatch(eTag)
            }
        } ?: noneMatch(null)

context(request: Request)
fun <T : Versionable> List<T>.ifNoneMatch(
    noneMatch: (ETag?) -> Response,
    match: (ETag) -> Response,
): Response =
    request.ifNoneMatch()
        ?.let { eTag ->
            if (eTag.value == md5()) {
                match(eTag)
            } else {
                noneMatch(eTag)
            }
        } ?: noneMatch(null)

context(request: Request)
fun <T : Modifiable> T.ifModifiedSince(
    modifiedSince: (Instant?) -> Response,
    notModifiedSince: (Instant) -> Response,
): Response =
    request.ifModifiedSince()
        ?.let { lastModified ->
            if (hasBeenModifiedSince(lastModified)) {
                modifiedSince(lastModified)
            } else {
                notModifiedSince(lastModified)
            }
        } ?: modifiedSince(null)

context(request: Request)
fun <T : Modifiable> List<T>.ifAnyModifiedSince(
    modifiedSince: (Instant?) -> Response,
    notModifiedSince: (Instant) -> Response,
): Response =
    request.ifModifiedSince()
        ?.let { lastModified ->
            if (any { it.hasBeenModifiedSince(lastModified) }) {
                modifiedSince(lastModified)
            } else {
                notModifiedSince(lastModified)
            }
        } ?: modifiedSince(null)
