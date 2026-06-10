package io.skrastrek.api.http4k.caching

import io.skrastrek.api.model.Modifiable
import io.skrastrek.api.model.Versionable
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Status.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ConditionalGetTest {
    @Test
    fun `if none match - ok`() {
        val request =
            Request(Method.GET, "/")
                .ifNoneMatch(ETag.strong("abc123"))

        val versionable =
            DummyVersionable(
                property1 = "value1",
                property2 = 42,
                property3 = listOf("item1", "item2"),
            )

        val response =
            with(request) {
                versionable.ifNoneMatch(
                    noneMatch = { _ -> Response(OK).body("Not matched") },
                    match = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, OK)
    }

    @Test
    fun `if none match - ok (header missing)`() {
        val requestWithoutHeader =
            Request(Method.GET, "/")

        val versionable =
            DummyVersionable(
                property1 = "value1",
                property2 = 42,
                property3 = listOf("item1", "item2"),
            )

        val response =
            with(requestWithoutHeader) {
                versionable.ifNoneMatch(
                    noneMatch = { _ -> Response(OK).body("Not matched") },
                    match = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, OK)
    }

    @Test
    fun `if none match - not modified`() {
        val versionable =
            DummyVersionable(
                property1 = "value1",
                property2 = 42,
                property3 = listOf("item1", "item2"),
            )

        val request =
            Request(Method.GET, "/")
                .ifNoneMatch(ETag.strong(versionable.md5()))

        val response =
            with(request) {
                versionable.ifNoneMatch(
                    noneMatch = { _ -> Response(OK).body("Not matched") },
                    match = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, NOT_MODIFIED)
    }

    @Test
    fun `if modified since - ok`() {
        val timestamp = Clock.System.now()

        val request =
            Request(Method.GET, "/")
                .ifModifiedSince(timestamp)

        val modifiable =
            DummyModifiable(
                createdAt = timestamp.plus(12.hours),
                updatedAt = timestamp.plus(24.hours),
            )

        val response =
            with(request) {
                modifiable.ifModifiedSince(
                    modifiedSince = { _ -> Response(OK).body("Modified") },
                    notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, OK)
    }

    @Test
    fun `if modified since - ok (header missing)`() {
        val timestamp = Clock.System.now()

        val requestWithoutHeader =
            Request(Method.GET, "/")

        val modifiable =
            DummyModifiable(
                createdAt = timestamp,
                updatedAt = null,
            )

        val response =
            with(requestWithoutHeader) {
                modifiable.ifModifiedSince(
                    modifiedSince = { _ -> Response(OK).body("Modified") },
                    notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, OK)
    }

    @Test
    fun `if modified since - not modified`() {
        val timestamp = Clock.System.now()

        val request =
            Request(Method.GET, "/")
                .ifModifiedSince(timestamp.plus(24.hours))

        val modifiable =
            DummyModifiable(
                createdAt = timestamp,
                updatedAt = null,
            )

        val response =
            with(request) {
                modifiable.ifModifiedSince(
                    modifiedSince = { _ -> Response(OK).body("Modified") },
                    notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                )
            }

        assertEquals(response.status, NOT_MODIFIED)
    }

    private data class DummyModifiable(
        override val createdAt: Instant,
        override val updatedAt: Instant?,
    ) : Modifiable

    private data class DummyVersionable(
        val property1: String,
        val property2: Int,
        val property3: List<String>,
    ) : Versionable
}
