package io.skrastrek.api.http4k.auth

interface TokenValidator<out C> {
    fun validate(token: String): C
}
