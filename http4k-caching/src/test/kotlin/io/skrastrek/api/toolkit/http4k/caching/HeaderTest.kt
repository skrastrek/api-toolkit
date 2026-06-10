package io.skrastrek.api.toolkit.http4k.caching

import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HeaderTest {
    @Test
    fun ifModifiedSince() {
        val timestamp = Instant.parse("2026-02-20T13:56:26Z")

        val request =
            Request(Method.GET, "/")
                .ifModifiedSince(timestamp)

        val headerValue = request.header("If-Modified-Since")

        assertEquals(headerValue, "Fri, 20 Feb 2026 13:56:26 GMT")
        assertEquals(request.ifModifiedSince(), timestamp)
    }

    @Test
    fun lastModified() {
        val timestamp = Instant.parse("2026-02-20T13:56:26Z")

        val response =
            Response(OK)
                .lastModified(timestamp)

        val headerValue = response.header("Last-Modified")

        assertEquals(headerValue, "Fri, 20 Feb 2026 13:56:26 GMT")
    }

    @Test
    fun eTag() {
        val eTag = ETag.strong("abc123")

        val response =
            Response(OK)
                .eTag(eTag)

        val headerValue = response.header("ETag")

        assertEquals(headerValue, """"abc123"""")
    }

    @Test
    fun ifNoneMatch() {
        val eTag = ETag.strong("abc123")

        val request =
            Request(Method.GET, "/")
                .ifNoneMatch(eTag)

        val headerValue = request.header("If-None-Match")

        assertEquals(headerValue, """"abc123"""")
        assertEquals(request.ifNoneMatch(), eTag)
    }
}
