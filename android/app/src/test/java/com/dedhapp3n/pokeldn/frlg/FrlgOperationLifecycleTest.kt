package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrlgOperationLifecycleTest {
    @Test
    fun operationCannotReportCompletionBeforeAdapterCleanup() {
        val lifecycle = FrlgOperationLifecycle()
        lifecycle.begin()
        lifecycle.advance(FrlgOperationPhase.WAITING_FOR_CONSOLE)

        assertThrows(IllegalStateException::class.java) {
            lifecycle.adapterReady(FrlgOperationPhase.COMPLETED)
        }
    }

    @Test
    fun terminalPhaseCannotBypassCleanupThroughAdvance() {
        val lifecycle = FrlgOperationLifecycle()
        lifecycle.begin()

        assertThrows(IllegalArgumentException::class.java) {
            lifecycle.advance(FrlgOperationPhase.COMPLETED)
        }
    }

    @Test
    fun cleanedOperationCanCompleteAndAnotherCanStart() {
        val lifecycle = FrlgOperationLifecycle()
        lifecycle.begin()
        lifecycle.beginCleanup("CMD_STOP acknowledged")
        lifecycle.adapterReady(FrlgOperationPhase.COMPLETED)
        assertEquals(FrlgOperationPhase.COMPLETED, lifecycle.state.phase)

        lifecycle.begin()
        assertEquals(FrlgOperationPhase.PREPARING_ESP32, lifecycle.state.phase)
    }
}
