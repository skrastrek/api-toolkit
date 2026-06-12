package io.skrastrek.api.toolkit.http4k.caching

import io.skrastrek.api.toolkit.model.Versionable
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResponseTest {
    private data class Item(
        val id: String,
    ) : Versionable

    @Test
    fun `notModifiedResponse returns 304`() {
        val response = Item("a").notModifiedResponse()

        assertEquals(NOT_MODIFIED, response.status)
    }

    @Test
    fun `notModifiedResponse sets ETag matching versionable`() {
        val item = Item("a")

        val response = item.notModifiedResponse()

        assertEquals(Response(NOT_MODIFIED).eTag(item).header("ETag"), response.header("ETag"))
    }

    @Test
    fun `list notModifiedResponse returns 304`() {
        val response = listOf(Item("a"), Item("b")).notModifiedResponse()

        assertEquals(NOT_MODIFIED, response.status)
    }

    @Test
    fun `list notModifiedResponse sets ETag matching list`() {
        val items = listOf(Item("a"), Item("b"))

        val response = items.notModifiedResponse()

        assertNotNull(response.header("ETag"))
        assertEquals(Response(NOT_MODIFIED).eTag(items).header("ETag"), response.header("ETag"))
    }

    @Test
    fun `contentLocation sets Content-Location header`() {
        val uri = Uri.of("/resources/123")

        val response = Response(OK).contentLocation(uri)

        assertEquals("/resources/123", response.header("content-location"))
    }
}
