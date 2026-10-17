package com.dedhapp3n.pokeldn.ui

import com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SaveRestoreUiTest {
    @Test fun `cartridge row never substitutes ESP32 state`() {
        val waiting = restoreCartridgeLabel(null, FrlgOperationPhase.WAITING_FOR_CONSOLE, true)
        val failed = restoreCartridgeLabel(null, FrlgOperationPhase.FAILED, false)
        assertEquals("Waiting for console", waiting)
        assertEquals("Connection lost", failed)
        assertNotEquals("ESP32 disconnected", waiting)
        assertNotEquals("ESP32 disconnected", failed)
    }
}
