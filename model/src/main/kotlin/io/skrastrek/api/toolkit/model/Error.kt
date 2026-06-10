package io.skrastrek.api.toolkit.model

sealed class Error(
    override val message: String,
    override val cause: Throwable?,
) : RuntimeException() {
    companion object;

    abstract class InsufficientAccess(
        override val message: String,
    ) : Error(message, null)

    abstract class NotFound(
        override val message: String,
    ) : Error(message, null)

    open class InvalidRequest(
        override val message: String,
        override val cause: Throwable? = null,
    ) : Error(message.plus(cause?.message.orEmpty()), cause)

    abstract class Unauthorized(
        override val message: String,
        override val cause: Throwable? = null,
    ) : Error(message, cause)

    abstract class UnsupportedMediaType(
        override val message: String,
    ) : Error(message, null)

    data class Unexpected(
        override val message: String,
        override val cause: Throwable? = null,
    ) : Error(message, cause) {
        constructor(cause: Throwable) : this(cause.message.orEmpty(), cause)
    }
}
