package io.skrastrek.api.http4k.caching

import io.skrastrek.api.model.Versionable
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Uri
import org.http4k.core.with
import org.http4k.lens.Header

fun <T : Versionable> T.notModifiedResponse() =
    Response(NOT_MODIFIED)
        .eTag(this)

fun <T : Versionable> List<T>.notModifiedResponse() =
    Response(NOT_MODIFIED)
        .eTag(this)

fun Response.contentLocation(uri: Uri) = with(Header.CONTENT_LOCATION of uri)
