package io.skrastrek.api.http4k.auth

import io.skrastrek.api.model.Error
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.base64Decoded
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.lens.BiDiLens
import org.http4k.lens.bearerToken
import org.http4k.sse.SseFilter
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal class MissingToken : Error.Unauthorized("Missing bearer token.")

internal class MalformedJwt(
    reason: String,
) : Error.Unauthorized("Malformed JWT: $reason.")

internal class UnsupportedAlgorithm(
    alg: String?,
) : Error.Unauthorized("Unsupported JWT algorithm: $alg.")

internal class UnknownSigningKey : Error.Unauthorized("Unknown JWT signing key.")

internal class InvalidSignature : Error.Unauthorized("Invalid JWT signature.")

internal class InvalidIssuer : Error.Unauthorized("Invalid JWT issuer.")

internal class TokenExpired : Error.Unauthorized("JWT has expired.")

internal class UnauthorizedClient : Error.Unauthorized("JWT client_id is not allowed.")

@Serializable
data class JwkKey(
    val kid: String? = null,
    val kty: String,
    val alg: String? = null,
    val n: String,
    val e: String,
    val use: String? = null,
)

@Serializable
private data class JwkSet(
    val keys: List<JwkKey>,
)

fun interface JwkSetProvider {
    fun getKeys(): Map<String?, JwkKey>
}

/**
 * Validated JWT claims. Standard claims are extracted directly; [raw] exposes all
 * remaining claims for project-specific extraction (e.g. Cognito groups, custom attributes).
 */
data class JwtClaims(
    val sub: String,
    val clientId: String,
    val scope: List<String>,
    val raw: JsonObject,
) {
    fun isClientCredentials() = sub == clientId
}

private fun fetchJwks(issuer: String): Map<String?, JwkKey> {
    val response =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder()
                .uri(URI.create("$issuer/.well-known/jwks.json"))
                .GET()
                .build(),
            BodyHandlers.ofString(),
        )
    return json.decodeFromString<JwkSet>(response.body()).keys.associateBy { it.kid }
}

private val json by lazy { Json { ignoreUnknownKeys = true } }
private val base64UrlDecoder by lazy { Base64.getUrlDecoder() }

private const val CLOCK_SKEW_SECONDS = 30L

class JwtValidator(
    private val issuer: String,
    private val allowedClientIds: Set<String>,
    jwkSetProvider: JwkSetProvider = JwkSetProvider { fetchJwks(issuer) },
) : TokenValidator<JwtClaims> {
    private val keysByKid: Map<String?, JwkKey> by lazy { jwkSetProvider.getKeys() }

    fun <P : Any> authFilter(
        principalLens: BiDiLens<Request, P?>,
        toPrincipal: (JwtClaims) -> P,
    ): Filter =
        Filter { next ->
            { request ->
                val bearerToken = request.bearerToken() ?: throw MissingToken()
                next(principalLens(toPrincipal(validate(bearerToken)), request))
            }
        }

    fun <P : Any> optionalAuthFilter(
        principalLens: BiDiLens<Request, P?>,
        toPrincipal: (JwtClaims) -> P,
    ): Filter =
        Filter { next ->
            { request ->
                val bearerToken = request.bearerToken()
                if (bearerToken != null) {
                    next(principalLens(toPrincipal(validate(bearerToken)), request))
                } else {
                    next(request)
                }
            }
        }

    fun <P : Any> authOrPrincipalFilter(
        principalLens: BiDiLens<Request, P?>,
        toPrincipal: (JwtClaims) -> P,
    ): Filter =
        Filter { next ->
            { request ->
                if (principalLens(request) != null) {
                    next(request)
                } else {
                    authFilter(principalLens, toPrincipal)(next)(request)
                }
            }
        }

    fun <P : Any> authSseFilter(
        principalLens: BiDiLens<Request, P?>,
        toPrincipal: (JwtClaims) -> P,
    ): SseFilter =
        SseFilter { next ->
            handler@{ request: Request ->
                val bearerToken = request.bearerToken() ?: throw MissingToken()
                next(principalLens(toPrincipal(validate(bearerToken)), request))
            }
        }

    override fun validate(token: String): JwtClaims {
        val parts = token.split(".")
        if (parts.size != 3) throw MalformedJwt("expected 3 parts, got ${parts.size}")
        val (headerPart, payloadPart, signaturePart) = parts

        val header =
            runCatching { json.decodeFromString<JsonObject>(headerPart.base64Decoded()) }
                .getOrElse { throw MalformedJwt("invalid header") }
        val alg = header["alg"]?.jsonPrimitive?.content
        if (alg != "RS256") throw UnsupportedAlgorithm(alg)
        val kid = header["kid"]?.jsonPrimitive?.content
        val jwk = keysByKid[kid] ?: throw UnknownSigningKey()

        val publicKey =
            runCatching {
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(
                        BigInteger(1, base64UrlDecoder.decode(jwk.n)),
                        BigInteger(1, base64UrlDecoder.decode(jwk.e)),
                    ),
                )
            }.getOrElse { throw UnknownSigningKey() }

        val signatureValid =
            runCatching {
                Signature.getInstance("SHA256withRSA").let { sig ->
                    sig.initVerify(publicKey)
                    sig.update("$headerPart.$payloadPart".toByteArray(Charsets.US_ASCII))
                    sig.verify(base64UrlDecoder.decode(signaturePart))
                }
            }.getOrElse { throw InvalidSignature() }
        if (!signatureValid) throw InvalidSignature()

        val claims =
            runCatching { json.decodeFromString<JsonObject>(payloadPart.base64Decoded()) }
                .getOrElse { throw MalformedJwt("Invalid JWT payload") }
                .also { it.validateClaims() }

        return claims.toJwtClaims()
    }

    private fun JsonObject.validateClaims() {
        val iss = this["iss"]?.jsonPrimitive?.content
        val tokenUse = this["token_use"]?.jsonPrimitive?.content
        val clientId = this["client_id"]?.jsonPrimitive?.content
        val exp = this["exp"]?.jsonPrimitive?.content?.toLongOrNull()

        if (iss != issuer) throw InvalidIssuer()
        if (tokenUse != "access") throw MalformedJwt("unexpected token_use: $tokenUse")
        if (clientId == null || clientId !in allowedClientIds) throw UnauthorizedClient()
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (exp == null || exp + CLOCK_SKEW_SECONDS < nowSeconds) throw TokenExpired()
    }

    private fun JsonObject.toJwtClaims(): JwtClaims {
        val sub = this["sub"]?.jsonPrimitive?.content ?: throw MalformedJwt("Missing sub claim")
        val clientId = this["client_id"]?.jsonPrimitive?.content!!
        val scope =
            this["scope"]
                ?.jsonPrimitive
                ?.content
                ?.split(" ")
                .orEmpty()
        return JwtClaims(
            sub = sub,
            clientId = clientId,
            scope = scope,
            raw = this,
        )
    }
}
