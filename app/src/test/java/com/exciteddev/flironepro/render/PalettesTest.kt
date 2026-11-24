package com.exciteddev.flironepro.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {
    @Test
    fun palettesHaveDistinctEnds() {
        for (p in Palette.entries) {
            val l = Palettes.lut(p)
            assertEquals(256, l.size)
            assertTrue("$p", l[0] != l[255])
        }
    }
}
