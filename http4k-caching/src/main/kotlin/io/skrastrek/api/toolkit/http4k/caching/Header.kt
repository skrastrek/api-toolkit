package io.skrastrek.api.toolkit.http4k.caching

import io.skrastrek.api.toolkit.model.Versionable
import io.skrastrek.api.toolkit.model.md5
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents.Formats.RFC_1123
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.with
import org.http4k.lens.BiDiLensSpec
import org.http4k.lens.Header
import org.http4k.lens.uri
import kotlin.time.Instant

private val IF_MODIFIED_SINCE =
    rfc1123Header().optional("If-Modified-Since")

private val LAST_MODIFIED =
    rfc1123Header().optional("Last-Modified")

private val ETAG =
    eTagLens().optional("ETag")

private val IF_NONE_MATCH =
    eTagLens().optional("If-None-Match")

fun Request.ifModifiedSince() = IF_MODIFIED_SINCE(this)

fun Request.ifNoneMatch() = IF_NONE_MATCH(this)

fun Request.ifNoneMatch(value: ETag) = with(IF_NONE_MATCH of value)

fun Request.ifModifiedSince(timestamp: Instant) = with(IF_MODIFIED_SINCE of timestamp)

fun Response.eTag(value: ETag) = with(ETAG of value)

fun Response.eTag(value: Versionable) = eTag(ETag.strong(value.md5()))

fun Response.eTag(value: List<Versionable>) = eTag(ETag.strong(value.md5()))

fun Response.lastModified(timestamp: Instant) = with(LAST_MODIFIED of timestamp)

val Header.CONTENT_LOCATION
    get() = uri().required("content-location")

private fun rfc1123Header(): BiDiLensSpec<HttpMessage, Instant> =
    Header.map(
        { value -> RFC_1123.parse(value).toInstantUsingOffset() },
        { instant -> instant.format(RFC_1123) },
    )

private fun eTagLens() =
    Header.map(
        { value -> ETag.parse(value) },
        { eTag -> eTag.headerValue() },
    )
