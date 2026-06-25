package io.skrastrek.api.toolkit.model

interface Versionable {
    /**
     * @param seed optional extra context folded into the hash. Use it to make the version depend on
     * something beyond the entity's own fields — e.g. the representation that will actually be served
     * (requested language, which texts are translatable). An empty seed reproduces the bare version.
     */
    fun md5(seed: String = ""): String = (toString() + seed).md5()
}

fun <T : Versionable> List<T>.md5(seed: String = ""): String = (joinToString { it.md5() } + seed).md5()

internal fun String.md5(): String {
    val bytes =
        java.security.MessageDigest
            .getInstance("MD5")
            .digest(toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
