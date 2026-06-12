package io.skrastrek.api.toolkit.http4k.errors

import io.skrastrek.api.toolkit.model.Error
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.UNAUTHORIZED
import org.http4k.core.Status.Companion.UNSUPPORTED_MEDIA_TYPE
import org.http4k.lens.Header
import org.http4k.sse.SseResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class TestUnauthorized(
    message: String = "test unauthorized",
) : Error.Unauthorized(message)

private class TestInsufficientAccess(
    message: String = "test forbidden",
) : Error.InsufficientAccess(message)

private class TestNotFound(
    message: String = "test not found",
) : Error.NotFound(message)

private class TestUnsupportedMediaType(
    message: String = "test unsupported",
) : Error.UnsupportedMediaType(message)

class ErrorResponseTest {
    // --- toError() ---

    @Test
    fun `LensFailure converts to InvalidRequest`() {
        val ex = runCatching { Header.required("X-Missing")(Request(Method.GET, "/")) }.exceptionOrNull()!!

        assertIs<Error.InvalidRequest>(ex.toError())
    }

    @Test
    fun `Error subclass passes through unchanged`() {
        val original = TestNotFound()

        assertIs<TestNotFound>(original.toError())
    }

    @Test
    fun `generic Throwable converts to Unexpected`() {
        val error = RuntimeException("boom").toError()

        assertIs<Error.Unexpected>(error)
        assertEquals("boom", error.message)
    }

    // --- toResponse() ---

    @Test
    fun `InvalidRequest maps to 400 with message`() {
        val response = Error.InvalidRequest("bad input").toResponse()

        assertEquals(BAD_REQUEST, response.status)
        assertJsonBody(response, "bad input")
    }

    @Test
    fun `Unauthorized maps to 401`() {
        val response = TestUnauthorized().toResponse()

        assertEquals(UNAUTHORIZED, response.status)
        assertJsonContentType(response)
    }

    @Test
    fun `InsufficientAccess maps to 403 with message`() {
        val response = TestInsufficientAccess("no access").toResponse()

        assertEquals(FORBIDDEN, response.status)
        assertJsonBody(response, "no access")
    }

    @Test
    fun `NotFound maps to 404 with message`() {
        val response = TestNotFound("thing missing").toResponse()

        assertEquals(NOT_FOUND, response.status)
        assertJsonBody(response, "thing missing")
    }

    @Test
    fun `Unexpected maps to 500`() {
        val response = Error.Unexpected(RuntimeException("oops")).toResponse()

        assertEquals(INTERNAL_SERVER_ERROR, response.status)
        assertJsonContentType(response)
    }

    @Test
    fun `UnsupportedMediaType maps to 415 with message`() {
        val response = TestUnsupportedMediaType("need json").toResponse()

        assertEquals(UNSUPPORTED_MEDIA_TYPE, response.status)
        assertJsonBody(response, "need json")
    }

    // --- responseFilter() ---

    @Test
    fun `responseFilter passes through successful response`() {
        val errors = mutableListOf<Error>()
        val filter = Error.responseFilter { errors.add(it) }

        val response = filter { Response(OK) }(Request(Method.GET, "/"))

        assertEquals(OK, response.status)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `responseFilter catches Error and invokes onError`() {
        val errors = mutableListOf<Error>()
        val filter = Error.responseFilter { errors.add(it) }

        val response = filter { throw TestNotFound("gone") }(Request(Method.GET, "/"))

        assertEquals(NOT_FOUND, response.status)
        assertIs<TestNotFound>(errors.single())
    }

    @Test
    fun `responseFilter converts unknown Throwable to Unexpected`() {
        val errors = mutableListOf<Error>()
        val filter = Error.responseFilter { errors.add(it) }

        val response = filter { throw RuntimeException("crash") }(Request(Method.GET, "/"))

        assertEquals(INTERNAL_SERVER_ERROR, response.status)
        assertIs<Error.Unexpected>(errors.single())
    }

    // --- sseResponseFilter() ---

    @Test
    fun `sseResponseFilter passes through on success`() {
        val errors = mutableListOf<Error>()
        val sseFilter = Error.sseResponseFilter { errors.add(it) }

        val response = sseFilter { SseResponse(OK) {} }(Request(Method.GET, "/"))

        assertEquals(OK, response.status)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `sseResponseFilter catches Error and returns error status`() {
        val errors = mutableListOf<Error>()
        val sseFilter = Error.sseResponseFilter { errors.add(it) }

        val response = sseFilter { throw TestUnauthorized() }(Request(Method.GET, "/"))

        assertEquals(UNAUTHORIZED, response.status)
        assertIs<TestUnauthorized>(errors.single())
    }

    private fun assertJsonContentType(response: Response) {
        assertTrue(response.header("content-type")?.contains("application/json") == true)
    }

    private fun assertJsonBody(
        response: Response,
        message: String,
    ) {
        assertJsonContentType(response)
        assertTrue(response.bodyString().contains(message))
    }
}
