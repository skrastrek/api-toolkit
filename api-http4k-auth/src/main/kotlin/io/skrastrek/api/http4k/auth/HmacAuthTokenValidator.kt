package io.skrastrek.api.http4k.auth

import io.skrastrek.api.model.Error
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.lens.BiDiLens
import java.util.Base64

internal class MalformedHmacToken(
    reason: String,
) : Error.Unauthorized("Malformed HMAC token: $reason.")

internal class InvalidHmacSignature : Error.Unauthorized("Invalid HMAC token signature.")

internal class HmacTokenExpired : Error.Unauthorized("HMAC token has expired.")

private val base64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()

class HmacAuthTokenValidator<P>(
    private val secret: String,
    private val serializer: KSerializer<P>,
) : TokenValidator<P> {
    fun <T : Any> filter(
        principalLens: BiDiLens<Request, T?>,
        toPrincipal: (P) -> T,
    ): Filter =
        Filter { next ->
            { request ->
                if (principalLens(request) == null) {
                    val token = request.query("authToken")
                    if (token != null) {
                        next(principalLens(toPrincipal(validate(token)), request))
                    } else {
                        next(request)
                    }
                } else {
                    next(request)
                }
            }
        }

    override fun validate(token: String): P {
        val dotIndex = token.lastIndexOf('.')
        if (dotIndex < 0) throw MalformedHmacToken("missing separator")
        val envelopeEncoded = token.substring(0, dotIndex)
        val signaturePart = token.substring(dotIndex + 1)

        val expectedSig = hmacSha256(envelopeEncoded, secret)
        val providedSig =
            runCatching { base64UrlDecoder.decode(signaturePart) }
                .getOrElse { throw MalformedHmacToken("invalid signature encoding") }
        if (!expectedSig.contentEquals(providedSig)) throw InvalidHmacSignature()

        val envelopeJson =
            runCatching { String(base64UrlDecoder.decode(envelopeEncoded), Charsets.UTF_8) }
                .getOrElse { throw MalformedHmacToken("invalid envelope encoding") }
        val envelope =
            runCatching { hmacJson.decodeFromString(HmacEnvelope.serializer(), envelopeJson) }
                .getOrElse { throw MalformedHmacToken("invalid JSON envelope") }

        if (envelope.exp < System.currentTimeMillis() / 1000L) throw HmacTokenExpired()

        return runCatching { hmacJson.decodeFromJsonElement(serializer, envelope.payload) }
            .getOrElse { throw MalformedHmacToken("invalid payload") }
    }
}
