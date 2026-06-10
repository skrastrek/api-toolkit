package io.skrastrek.api.toolkit.http4k.auth

interface TokenValidator<out C> {
    fun validate(token: String): C
}
