package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun failedOperationReleasesOwnershipAndCanImmediatelyRestart() {
        val lifecycle = FrlgOperationLifecycle()
        lifecycle.begin()
        lifecycle.advance(FrlgOperationPhase.ESTABLISHING_RFU, "RFU request received")
        lifecycle.beginCleanup("Console disconnected")
        lifecycle.adapterReady(FrlgOperationPhase.FAILED, "Console disconnected")

        assertFalse(lifecycle.state.running)
        lifecycle.begin()
        assertTrue(lifecycle.state.running)
    }

    @Test
    fun startRejectionHasVisibleReason() {
        val availability = FrlgOperationStartGate.evaluate(
            keysAvailable = true,
            handshakeVerified = true,
            radioReady = true,
            frlgOperationOwned = true,
            otherRadioOperationOwned = false,
        )

        assertFalse(availability.enabled)
        assertEquals("A Walk Through Walls operation is still active or cleaning up", availability.reason)
    }

    @Test
    fun startIsDisabledUntilRealRadioSessionIsReady() {
        val availability = FrlgOperationStartGate.evaluate(
            keysAvailable = true,
            handshakeVerified = true,
            radioReady = false,
            frlgOperationOwned = false,
            otherRadioOperationOwned = false,
            radioDetail = "ESP32 radio is cleanup required",
        )

        assertFalse(availability.enabled)
        assertEquals("ESP32 radio is cleanup required", availability.reason)
    }

    @Test
    fun cleanupPreservesDeepestProtocolBoundary() {
        val reliable = FrlgOperationState(
            phase = FrlgOperationPhase.ESTABLISHING_RELIABLE,
            lastReached = FrlgOperationPhase.ESTABLISHING_RELIABLE,
            diagnostics = "Reliable=3/2",
        )
        val stationLeave = FrlgOperationState(
            phase = FrlgOperationPhase.AUTHENTICATING,
            detail = "Station left",
            lastReached = FrlgOperationPhase.AUTHENTICATING,
            diagnostics = "station=1/1",
        )

        val merged = reliable.recordProgress(stationLeave)
        assertEquals(FrlgOperationPhase.ESTABLISHING_RELIABLE, merged.lastReached)
        assertEquals("station=1/1", merged.diagnostics)
    }
}
