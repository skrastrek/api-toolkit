package io.skrastrek.api.toolkit.model

import kotlin.time.Instant

interface Modifiable {
    val createdAt: Instant
    val updatedAt: Instant?

    fun lastModifiedAt() = updatedAt ?: createdAt

    fun hasBeenModifiedSince(instant: Instant): Boolean = lastModifiedAt() > instant
}
