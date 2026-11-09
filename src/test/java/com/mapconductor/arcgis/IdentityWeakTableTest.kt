package com.mapconductor.arcgis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityWeakTableTest {
    /** ArcGIS の Layer のように、載せた後で hashCode / equals が変わる鍵。 */
    private class Shifty(
        var hash: Int,
    ) {
        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Shifty && other.hash == hash
    }

    @Test
    fun findsTheKeyAfterItsHashCodeChanges() {
        val table = IdentityWeakTable<Shifty, String>()
        val layer = Shifty(1)
        table.put(layer, "ground")

        layer.hash = 2

        assertEquals("ground", table[layer])
    }

    @Test
    fun doesNotConfuseEqualButDistinctKeys() {
        val table = IdentityWeakTable<Shifty, String>()
        val a = Shifty(7)
        val b = Shifty(7)
        table.put(a, "raster")

        assertNull(table[b])
        table.put(b, "ground")
        assertEquals("raster", table[a])
        assertEquals("ground", table[b])
        assertEquals(2, table.size)
    }

    @Test
    fun putReplacesTheValueOfTheSameKey() {
        val table = IdentityWeakTable<Shifty, String>()
        val a = Shifty(1)
        table.put(a, "raster")
        table.put(a, "ground")

        assertEquals("ground", table[a])
        assertEquals(1, table.size)
    }
}
