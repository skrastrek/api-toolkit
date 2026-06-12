package io.skrastrek.api.toolkit.http4k.auth

import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SECRET = "test-secret-value"
private val encoder = Base64.getUrlEncoder().withoutPadding()

@Serializable
private data class TestPayload(
    val username: String,
)

private val testSerializer = TestPayload.serializer()

class HmacTokenTest {
    private val generator = HmacAuthTokenGenerator(SECRET, testSerializer)
    private val validator = HmacAuthTokenValidator(SECRET, testSerializer)
    private val now get() = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    @Test
    fun `generateToken and validateToken roundtrip`() {
        val token = generator.generateToken(TestPayload("alice@example.com"), now)
        val payload = validator.validate(token)
        assertEquals("alice@example.com", payload.username)
    }

    @Test
    fun `tokens generated with same inputs are identical`() {
        val at = Instant.fromEpochSeconds(1_700_000_000L + 86400 * 365)
        assertEquals(generator.generateToken(TestPayload("alice"), at), generator.generateToken(TestPayload("alice"), at))
    }

    @Test
    fun `different payloads produce different tokens`() {
        assert(generator.generateToken(TestPayload("alice"), now) != generator.generateToken(TestPayload("bob"), now))
    }

    @Test
    fun `expired token throws HmacTokenExpired`() {
        val token = generator.generateToken(TestPayload("alice"), Instant.fromEpochSeconds(0L))
        assertFailsWith<HmacTokenExpired> { validator.validate(token) }
    }

    @Test
    fun `token with negative expiry throws HmacTokenExpired`() {
        val gen = HmacAuthTokenGenerator(SECRET, testSerializer, (-1).seconds)
        assertFailsWith<HmacTokenExpired> { validator.validate(gen.generateToken(TestPayload("alice"), now)) }
    }

    @Test
    fun `different secret throws InvalidHmacSignature`() {
        val otherToken = HmacAuthTokenGenerator("other-secret", testSerializer).generateToken(TestPayload("alice"), now)
        assertFailsWith<InvalidHmacSignature> { validator.validate(otherToken) }
    }

    @Test
    fun `tampered envelope throws InvalidHmacSignature`() {
        val token = generator.generateToken(TestPayload("alice"), now)
        val sig = token.substringAfterLast(".")
        val tampered = encoder.encodeToString("""{"payload":{"username":"evil"},"exp":9999999999}""".toByteArray())
        assertFailsWith<InvalidHmacSignature> { validator.validate("$tampered.$sig") }
    }

    @Test
    fun `invalid base64 signature throws MalformedHmacToken`() {
        val token = generator.generateToken(TestPayload("alice"), now)
        val e = assertFailsWith<MalformedHmacToken> { validator.validate("${token.substringBeforeLast(".")}.!!!invalid!!!") }
        assert(e.message.contains("invalid signature encoding"))
    }

    @Test
    fun `no dot separator throws MalformedHmacToken`() {
        val e = assertFailsWith<MalformedHmacToken> { validator.validate("nodottoken") }
        assert(e.message.contains("missing separator"))
    }

    @Test
    fun `non-json envelope throws MalformedHmacToken`() {
        val envelopeEncoded = encoder.encodeToString("not-json".toByteArray(Charsets.UTF_8))
        val sig = encoder.encodeToString(hmacSha256(envelopeEncoded, SECRET))
        val e = assertFailsWith<MalformedHmacToken> { validator.validate("$envelopeEncoded.$sig") }
        assert(e.message.contains("invalid JSON envelope"))
    }
}
