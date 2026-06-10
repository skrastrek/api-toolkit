package io.skrastrek.api.http4k.caching

import kotlin.test.Test
import kotlin.test.assertEquals

class ETagTest {
    @Test
    fun `ETag header value is correctly formatted`() {
        val weakETag = ETag.weak("abc123")
        val strongETag = ETag.strong("def456")

        assertEquals(strongETag.headerValue(), """"def456"""")
        assertEquals(weakETag.headerValue(), """W/"abc123"""")
    }

    @Test
    fun `ETag parsing correctly identifies weak and strong ETags`() {
        val weakETag = ETag.parse("""W/"abc123"""")
        val strongETag = ETag.parse(""""def456"""")

        assertEquals(weakETag.value, "abc123")
        assertEquals(weakETag.headerValue(), """W/"abc123"""")
        assertEquals(strongETag.value, "def456")
        assertEquals(strongETag.headerValue(), """"def456"""")
    }
}
