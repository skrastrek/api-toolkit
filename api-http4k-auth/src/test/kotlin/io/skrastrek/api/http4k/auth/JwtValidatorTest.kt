package io.skrastrek.api.http4k.auth

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ISSUER = "https://auth.example.com"
private const val CLIENT_ID = "test-client"
private const val KID = "test-key-1"

class JwtValidatorTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val validator =
        JwtValidator(
            issuer = ISSUER,
            allowedClientIds = setOf(CLIENT_ID),
            jwkSetProvider = fakeJwkSetProvider(keyPair),
        )

    // --- Happy paths ---

    @Test
    fun `valid user token returns non-client-credentials JwtClaims`() {
        val claims =
            validator.validate(
                buildToken(
                    keyPair = keyPair,
                    sub = "user-abc",
                    clientId = CLIENT_ID,
                    email = "alice@example.com",
                    givenName = "Alice",
                    familyName = "Smith",
                    groups = listOf("organizers"),
                ),
            )
        assertEquals("user-abc", claims.sub)
        assertEquals(CLIENT_ID, claims.clientId)
        assertFalse(claims.isClientCredentials())
        assertEquals("alice@example.com", claims.raw["email"]?.jsonPrimitive?.content)
        assertEquals("Alice", claims.raw["given_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `valid client credentials token returns isClientCredentials true`() {
        val claims =
            validator.validate(
                buildToken(keyPair = keyPair, sub = CLIENT_ID, clientId = CLIENT_ID, email = null),
            )
        assertEquals(CLIENT_ID, claims.sub)
        assertTrue(claims.isClientCredentials())
    }

    // --- Structural failures ---

    @Test
    fun `token with wrong number of parts throws MalformedJwt`() {
        assertFailsWith<MalformedJwt> { validator.validate("only.two") }
    }

    @Test
    fun `non-base64 header throws MalformedJwt`() {
        assertFailsWith<MalformedJwt> { validator.validate("!!!.payload.sig") }
    }

    // --- Algorithm check ---

    @Test
    fun `token with unsupported algorithm throws UnsupportedAlgorithm`() {
        assertFailsWith<UnsupportedAlgorithm> { validator.validate(buildToken(keyPair = keyPair, alg = "HS256")) }
    }

    @Test
    fun `token with missing alg header throws UnsupportedAlgorithm`() {
        val token =
            signToken(keyPair, """{"kid":"$KID"}""", buildPayloadJson(iss = ISSUER, sub = "u", clientId = CLIENT_ID, exp = futureExp()))
        assertFailsWith<UnsupportedAlgorithm> { validator.validate(token) }
    }

    // --- Key lookup ---

    @Test
    fun `token signed by different key throws InvalidSignature`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<InvalidSignature> { validator.validate(buildToken(keyPair = other)) }
    }

    @Test
    fun `token with unknown kid throws UnknownSigningKey`() {
        assertFailsWith<UnknownSigningKey> { validator.validate(buildToken(keyPair = keyPair, kid = "unknown-kid")) }
    }

    // --- Signature ---

    @Test
    fun `token with tampered payload throws InvalidSignature`() {
        val parts = buildToken(keyPair = keyPair).split(".")
        val tampered =
            """{"iss":"$ISSUER","sub":"x","client_id":"$CLIENT_ID","exp":${futureExp()},"token_use":"access","scope":"openid","email":"a@b.com"}"""
        val tp = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.toByteArray())
        assertFailsWith<InvalidSignature> { validator.validate("${parts[0]}.$tp.${parts[2]}") }
    }

    // --- Claim validation ---

    @Test
    fun `wrong issuer throws InvalidIssuer`() {
        assertFailsWith<InvalidIssuer> { validator.validate(buildToken(keyPair = keyPair, iss = "https://evil.example.com")) }
    }

    @Test
    fun `wrong token_use throws MalformedJwt`() {
        assertFailsWith<MalformedJwt> { validator.validate(buildToken(keyPair = keyPair, tokenUse = "id")) }
    }

    @Test
    fun `unauthorized client_id throws UnauthorizedClient`() {
        assertFailsWith<UnauthorizedClient> { validator.validate(buildToken(keyPair = keyPair, clientId = "other")) }
    }

    @Test
    fun `expired token throws TokenExpired`() {
        assertFailsWith<TokenExpired> { validator.validate(buildToken(keyPair = keyPair, exp = System.currentTimeMillis() / 1000 - 3600)) }
    }

    @Test
    fun `token expiring within clock skew is accepted`() {
        validator.validate(buildToken(keyPair = keyPair, exp = System.currentTimeMillis() / 1000 - 20))
    }
}

// --- Test helpers ---

private val jsonPrimitive get() = kotlinx.serialization.json.JsonPrimitive("_")
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as? kotlinx.serialization.json.JsonPrimitive

private fun fakeJwkSetProvider(
    keyPair: KeyPair,
    kid: String = KID,
): JwkSetProvider {
    val rsaPub = keyPair.public as RSAPublicKey
    val enc = Base64.getUrlEncoder().withoutPadding()
    val jwk =
        JwkKey(
            kid = kid,
            kty = "RSA",
            n = enc.encodeToString(rsaPub.modulus.toUnsignedByteArray()),
            e = enc.encodeToString(rsaPub.publicExponent.toUnsignedByteArray()),
        )
    return JwkSetProvider { mapOf(kid to jwk) }
}

private fun BigInteger.toUnsignedByteArray(): ByteArray {
    val b = toByteArray()
    return if (b[0] == 0.toByte() && b.size > 1) b.drop(1).toByteArray() else b
}

private fun futureExp() = System.currentTimeMillis() / 1000 + 3600

private fun buildPayloadJson(
    iss: String,
    sub: String,
    clientId: String,
    exp: Long,
    tokenUse: String = "access",
    scope: String = "openid",
    email: String? = null,
    givenName: String? = null,
    familyName: String? = null,
    groups: List<String> = emptyList(),
): String =
    buildString {
        append("""{"iss":"$iss","sub":"$sub","client_id":"$clientId","exp":$exp,"token_use":"$tokenUse","scope":"$scope"""")
        if (email != null) append(""","email":"$email"""")
        if (givenName != null) append(""","given_name":"$givenName"""")
        if (familyName != null) append(""","family_name":"$familyName"""")
        if (groups.isNotEmpty()) append(""","cognito:groups":${groups.joinToString(",", "[", "]") { "\"$it\"" }}""")
        append("}")
    }

private fun signToken(
    keyPair: KeyPair,
    headerJson: String,
    payloadJson: String,
): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val h = enc.encodeToString(headerJson.toByteArray())
    val p = enc.encodeToString(payloadJson.toByteArray())
    val sig =
        Signature.getInstance("SHA256withRSA").also {
            it.initSign(keyPair.private)
            it.update("$h.$p".toByteArray(Charsets.US_ASCII))
        }.sign()
    return "$h.$p.${enc.encodeToString(sig)}"
}

private fun buildToken(
    keyPair: KeyPair,
    kid: String = KID,
    alg: String = "RS256",
    iss: String = ISSUER,
    sub: String = "user-abc",
    clientId: String = CLIENT_ID,
    email: String? = "alice@example.com",
    givenName: String? = null,
    familyName: String? = null,
    groups: List<String> = emptyList(),
    exp: Long = futureExp(),
    tokenUse: String = "access",
    scope: String = "openid",
): String =
    signToken(
        keyPair,
        """{"alg":"$alg","kid":"$kid"}""",
        buildPayloadJson(
            iss = iss,
            sub = sub,
            clientId = clientId,
            exp = exp,
            tokenUse = tokenUse,
            scope = scope,
            email = email,
            givenName = givenName,
            familyName = familyName,
            groups = groups,
        ),
    )
