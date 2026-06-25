package io.skrastrek.api.toolkit.http4k.caching

import io.skrastrek.api.toolkit.model.Modifiable
import io.skrastrek.api.toolkit.model.Versionable
import kotlinx.coroutines.test.runTest
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_MODIFIED
import org.http4k.core.Status.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ConditionalGetTest {
    @Test
    fun `if none match - ok`() =
        runTest {
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
    fun `if none match - ok (header missing)`() =
        runTest {
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
    fun `if none match - not modified`() =
        runTest {
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
    fun `if none match - ok (seed changes version)`() =
        runTest {
            val versionable =
                DummyVersionable(
                    property1 = "value1",
                    property2 = 42,
                    property3 = listOf("item1", "item2"),
                )

            // Request carries the bare (seedless) ETag, but the served representation now has a seed.
            val request =
                Request(Method.GET, "/")
                    .ifNoneMatch(ETag.strong(versionable.md5()))

            val response =
                with(request) {
                    versionable.ifNoneMatch(
                        seed = "lang=no",
                        noneMatch = { _ -> Response(OK) },
                        match = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(OK, response.status)
        }

    @Test
    fun `if none match - not modified (matching seed)`() =
        runTest {
            val versionable =
                DummyVersionable(
                    property1 = "value1",
                    property2 = 42,
                    property3 = listOf("item1", "item2"),
                )

            val request =
                Request(Method.GET, "/")
                    .ifNoneMatch(ETag.strong(versionable.md5(seed = "lang=no")))

            val response =
                with(request) {
                    versionable.ifNoneMatch(
                        seed = "lang=no",
                        noneMatch = { _ -> Response(OK) },
                        match = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(NOT_MODIFIED, response.status)
        }

    @Test
    fun `if modified since - ok`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)

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
    fun `if modified since - ok (header missing)`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)

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
    fun `if modified since - not modified`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)

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

    @Test
    fun `list if none match - not modified`() =
        runTest {
            val items =
                listOf(
                    DummyVersionable("a", 1, emptyList()),
                    DummyVersionable("b", 2, emptyList()),
                )
            val listETag = ETag.parse(items.notModifiedResponse().header("ETag")!!)

            val response =
                with(Request(Method.GET, "/").ifNoneMatch(listETag)) {
                    items.ifNoneMatch(
                        noneMatch = { _ -> Response(OK) },
                        match = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(NOT_MODIFIED, response.status)
        }

    @Test
    fun `list if none match - ok (different ETag)`() =
        runTest {
            val items =
                listOf(
                    DummyVersionable("a", 1, emptyList()),
                    DummyVersionable("b", 2, emptyList()),
                )

            val response =
                with(Request(Method.GET, "/").ifNoneMatch(ETag.strong("stale"))) {
                    items.ifNoneMatch(
                        noneMatch = { _ -> Response(OK) },
                        match = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(OK, response.status)
        }

    @Test
    fun `list if none match - ok (header missing)`() =
        runTest {
            val items = listOf(DummyVersionable("a", 1, emptyList()))

            val response =
                with(Request(Method.GET, "/")) {
                    items.ifNoneMatch(
                        noneMatch = { _ -> Response(OK) },
                        match = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(OK, response.status)
        }

    @Test
    fun `list if any modified since - ok (one item newer)`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)
            val items =
                listOf(
                    DummyModifiable(createdAt = timestamp.minus(1.hours), updatedAt = null),
                    DummyModifiable(createdAt = timestamp.plus(1.hours), updatedAt = null),
                )

            val response =
                with(Request(Method.GET, "/").ifModifiedSince(timestamp)) {
                    items.ifAnyModifiedSince(
                        modifiedSince = { _ -> Response(OK) },
                        notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(OK, response.status)
        }

    @Test
    fun `list if any modified since - not modified (all items older)`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)
            val items =
                listOf(
                    DummyModifiable(createdAt = timestamp.minus(2.hours), updatedAt = null),
                    DummyModifiable(createdAt = timestamp.minus(1.hours), updatedAt = null),
                )

            val response =
                with(Request(Method.GET, "/").ifModifiedSince(timestamp)) {
                    items.ifAnyModifiedSince(
                        modifiedSince = { _ -> Response(OK) },
                        notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(NOT_MODIFIED, response.status)
        }

    @Test
    fun `list if any modified since - ok (header missing)`() =
        runTest {
            val timestamp = Instant.fromEpochSeconds(0)
            val items = listOf(DummyModifiable(createdAt = timestamp, updatedAt = null))

            val response =
                with(Request(Method.GET, "/")) {
                    items.ifAnyModifiedSince(
                        modifiedSince = { _ -> Response(OK) },
                        notModifiedSince = { _ -> Response(NOT_MODIFIED) },
                    )
                }

            assertEquals(OK, response.status)
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
