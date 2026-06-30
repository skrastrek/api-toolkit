package io.skrastrek.api.toolkit.http4k.caching

import io.skrastrek.api.toolkit.model.Versionable
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Uri
import org.http4k.core.with
import org.http4k.lens.Header

fun <T : Versionable> T.notModifiedResponse(seed: String = "") =
    Response(NOT_MODIFIED)
        .eTag(this, seed)

fun <T : Versionable> List<T>.notModifiedResponse(seed: String = "") =
    Response(NOT_MODIFIED)
        .eTag(this, seed)

fun Response.contentLocation(uri: Uri) = with(Header.CONTENT_LOCATION of uri)

fun Response.proxyRevalidate() = replaceHeader("Cache-Control", "proxy-revalidate".ensureOnlyOnceIn(header("Cache-Control")))

private fun String.ensureOnlyOnceIn(currentValue: String?): String =
    currentValue
        ?.split(",")
        ?.map(String::trim)
        ?.toSet()
        ?.plus(this)
        ?.joinToString(", ") ?: this
