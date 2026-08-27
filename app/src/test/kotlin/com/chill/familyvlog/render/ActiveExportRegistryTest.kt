package com.chill.familyvlog.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveExportRegistryTest {
    @Test
    fun `rejects concurrency and returns only the matching active export`() {
        val registry = ActiveExportRegistry<String>()
        val firstId = Any()
        val secondId = Any()

        assertTrue(registry.tryAcquire(firstId, "first"))
        assertFalse(registry.tryAcquire(secondId, "second"))
        assertNull(registry.take(secondId))
        assertEquals("first", registry.take(firstId))
        assertNull(registry.take(firstId))
    }

    @Test
    fun `late cancellation token cannot take a later export`() {
        val registry = ActiveExportRegistry<String>()
        val oldId = Any()
        val newId = Any()

        assertTrue(registry.tryAcquire(oldId, "old"))
        assertEquals("old", registry.take(oldId))
        assertTrue(registry.tryAcquire(newId, "new"))

        assertNull(registry.take(oldId))
        assertEquals("new", registry.take(newId))
    }
}
