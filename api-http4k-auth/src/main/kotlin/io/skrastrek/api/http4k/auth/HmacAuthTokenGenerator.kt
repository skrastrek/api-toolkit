package io.skrastrek.api.http4k.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Serializable
internal data class HmacEnvelope(
    val payload: JsonElement,
    val exp: Long,
)

internal val hmacJson = Json { ignoreUnknownKeys = true }
internal val base64UrlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

class HmacAuthTokenGenerator<P>(
    private val secret: String,
    private val serializer: KSerializer<P>,
    private val tokenExpiry: Duration = 30.days,
) {
    fun generateToken(
        payload: P,
        createdAt: Instant,
    ): String {
        val exp = (createdAt + tokenExpiry).epochSeconds
        val payloadElement = hmacJson.encodeToJsonElement(serializer, payload)
        val envelope = HmacEnvelope(payload = payloadElement, exp = exp)
        val envelopeEncoded =
            hmacJson.encodeToString(HmacEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
                .base64UrlEncode()
        val signature = hmacSha256(envelopeEncoded, secret).base64UrlEncode()
        return "$envelopeEncoded.$signature"
    }

    private fun ByteArray.base64UrlEncode(): String = base64UrlEncoder.encodeToString(this)
}

internal fun hmacSha256(
    data: String,
    secret: String,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
}
