package io.skrastrek.api.toolkit.http4k.openapi

import org.http4k.format.ConfigurableKotlinxSerialization

object KotlinxSerialization : ConfigurableKotlinxSerialization({
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
})
