package io.skrastrek.api.toolkit.http4k.auth

import kotlinx.serialization.Serializable
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.lens.RequestKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private const val FILTER_TEST_SECRET = "hmac-filter-test-secret"

@Serializable
private data class FilterTestPayload(
    val userId: String,
)

private data class FilterTestPrincipal(
    val userId: String,
)

class HmacFilterTest {
    private val serializer = FilterTestPayload.serializer()
    private val generator = HmacAuthTokenGenerator(FILTER_TEST_SECRET, serializer)
    private val validator = HmacAuthTokenValidator(FILTER_TEST_SECRET, serializer)
    private val principalLens = RequestKey.optional<FilterTestPrincipal>("principal")
    private val toPrincipal: (FilterTestPayload) -> FilterTestPrincipal = { FilterTestPrincipal(it.userId) }
    private val filter = validator.filter(principalLens, toPrincipal)

    @Test
    fun `filter passes through when principal already set`() {
        val existing = FilterTestPrincipal("existing-user")
        var capturedPrincipal: FilterTestPrincipal? = null

        val handler =
            filter { request ->
                capturedPrincipal = principalLens(request)
                Response(OK)
            }

        handler(principalLens(existing, Request(Method.GET, "/")))

        assertEquals(existing, capturedPrincipal)
    }

    @Test
    fun `filter passes through without principal when no authToken query param`() {
        var capturedPrincipal: FilterTestPrincipal? = null

        val handler =
            filter { request ->
                capturedPrincipal = principalLens(request)
                Response(OK)
            }

        handler(Request(Method.GET, "/"))

        assertNull(capturedPrincipal)
    }

    @Test
    fun `filter injects principal from valid authToken query param`() {
        val token = generator.generateToken(FilterTestPayload("alice"), Instant.fromEpochMilliseconds(System.currentTimeMillis()))
        var capturedPrincipal: FilterTestPrincipal? = null

        val handler =
            filter { request ->
                capturedPrincipal = principalLens(request)
                Response(OK)
            }

        handler(Request(Method.GET, "/").query("authToken", token))

        assertEquals(FilterTestPrincipal("alice"), capturedPrincipal)
    }
}
