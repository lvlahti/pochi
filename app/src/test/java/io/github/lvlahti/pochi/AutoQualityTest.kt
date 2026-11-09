package io.github.lvlahti.pochi

import org.junit.Assert.*
import org.junit.Test

class AutoQualityTest {
    @Test fun reducesAfterCongestionAndRecoversGradually() {
        val quality = AutoQuality(2500)
        assertEquals(1800, quality.update(true))
        assertEquals(1800, quality.update(false))
        assertEquals(1800, quality.update(false))
        assertEquals(1980, quality.update(false))
        repeat(40) { quality.update(false) }
        assertEquals(2500, quality.bitrateKbps)
    }
    @Test fun respectsFloorAtLowBitrates() {
        val quality = AutoQuality(500)
        repeat(30) { quality.update(true) }
        assertEquals(350, quality.bitrateKbps)
    }
}
