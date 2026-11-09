package io.github.lvlahti.pochi

import org.junit.Assert.*
import org.junit.Test

class ViewerGateTest {
    private val id = "0123456789abcdef0123456789abcdef"
    @Test fun noMediaBeforeApprovalAndCorrectIpOnly() {
        val gate = ViewerGate()
        assertEquals(ViewerGate.State.PENDING, gate.request(id, "192.168.42.2").state)
        assertFalse(gate.authorized(id, "192.168.42.2"))
        assertEquals(ViewerGate.State.INVALID, gate.request(id, "192.168.42.3").state)
        assertTrue(gate.decide(id, true))
        assertTrue(gate.authorized(id, "192.168.42.2"))
        assertFalse(gate.authorized(id, "192.168.42.3"))
    }
    @Test fun denialSticksAndRequiresNewViewer() {
        val gate = ViewerGate()
        gate.request(id, "10.0.0.5")
        assertTrue(gate.decide(id, false))
        assertEquals(ViewerGate.State.DENIED, gate.request(id, "10.0.0.5").state)
        assertFalse(gate.authorized(id, "10.0.0.5"))
        assertFalse(gate.decide(id, true))
    }
    @Test fun pendingExpiryAndIdValidation() {
        var now = 5000L
        val gate = ViewerGate { now }
        assertEquals(ViewerGate.State.INVALID, gate.request("bad", "1.1.1.1").state)
        gate.request(id, "1.1.1.1")
        assertEquals(1, gate.pending().size)
        now += 121_000L
        assertTrue(gate.request(id, "1.1.1.1").isNew)
    }
}
