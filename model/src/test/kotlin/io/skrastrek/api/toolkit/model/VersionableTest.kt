package io.skrastrek.api.toolkit.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionableTest {
    @Test
    fun md5() {
        val versionable =
            DummyVersionable(
                stringProperty = "value1",
                intProperty = 42,
            )

        assertEquals(versionable.md5(), "9904da6583089fceb091a1379dfb71e4")
    }

    @Test
    fun `md5 of array`() {
        val versions =
            listOf(
                DummyVersionable(stringProperty = "1", intProperty = 1),
                DummyVersionable(stringProperty = "2", intProperty = 2),
                DummyVersionable(stringProperty = "3", intProperty = 3),
            )

        assertEquals(versions.md5(), "dabf82027636d2b8e50ee2ebfe727ca7")
    }

    private data class DummyVersionable(
        val stringProperty: String,
        val intProperty: Int,
    ) : Versionable
}
