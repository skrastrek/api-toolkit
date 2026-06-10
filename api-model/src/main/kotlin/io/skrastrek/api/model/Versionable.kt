package io.skrastrek.api.model

interface Versionable {
    fun md5(): String = toString().md5()
}

fun <T : Versionable> List<T>.md5(): String = joinToString { it.md5() }.md5()

internal fun String.md5(): String {
    val bytes =
        java.security.MessageDigest
            .getInstance("MD5")
            .digest(toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
