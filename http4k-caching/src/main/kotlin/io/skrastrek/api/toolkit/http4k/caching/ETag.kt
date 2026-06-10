package io.skrastrek.api.toolkit.http4k.caching

sealed interface ETag {
    val value: String

    fun headerValue(): String

    private data class Weak(
        override val value: String,
    ) : ETag {
        init {
            require(value.isNotBlank()) { "Value must not be blank." }
        }

        override fun headerValue() = """W/"$value""""
    }

    private data class Strong(
        override val value: String,
    ) : ETag {
        init {
            require(value.isNotBlank()) { "Value must not be blank." }
        }

        override fun headerValue() = """"$value""""
    }

    companion object {
        fun weak(value: String): ETag = Weak(value)

        fun strong(value: String): ETag = Strong(value)

        fun parse(value: String): ETag =
            if (value.startsWith("W/")) {
                weak(value.removePrefix("W/").removeSurrounding("\""))
            } else {
                strong(value.removeSurrounding("\""))
            }
    }
}
