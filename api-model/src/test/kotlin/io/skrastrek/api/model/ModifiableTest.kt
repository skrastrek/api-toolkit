package io.skrastrek.api.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ModifiableTest {
    @Test
    fun lastModifiedAt() {
        val notUpdated =
            DummyModifiable(
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                updatedAt = null,
            )

        val updated = notUpdated.copy(updatedAt = Instant.parse("2025-01-01T00:00:00Z"))

        assertEquals(notUpdated.lastModifiedAt(), notUpdated.createdAt)
        assertEquals(updated.lastModifiedAt(), updated.updatedAt)
    }

    @Test
    fun hasBeenModifiedSince() {
        val modifiable =
            DummyModifiable(
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                updatedAt = null,
            )

        assertFalse(modifiable.hasBeenModifiedSince(Clock.System.now()))

        val now = Clock.System.now()
        val recentlyModified = modifiable.copy(updatedAt = now)

        assertFalse(recentlyModified.hasBeenModifiedSince(now))
        assertTrue(recentlyModified.hasBeenModifiedSince(now.minus(1.minutes)))
    }

    private data class DummyModifiable(
        override val createdAt: Instant,
        override val updatedAt: Instant?,
    ) : Modifiable
}
