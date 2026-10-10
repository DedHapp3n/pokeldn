package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.ldn.LdnDiscoverySnapshot
import com.dedhapp3n.pokeldn.ldn.LdnPiaStage
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

    @Test
    fun failureDiagnosticsIncludePreservedPostAuthenticationEvidence() {
        val diagnostics = frlgFailureDiagnostics(
            LdnDiscoverySnapshot(
                discoveryActivityCount = 7,
                ethernetFrames = 3,
                stationJoins = 1,
                stationLeaves = 1,
                authenticationRequests = 1,
                authenticationResponses = 1,
                participantRegistered = false,
                participantEverRegistered = true,
                registeredAdvertisementsSent = 6,
                participantIndex = 1,
                participantIp = "169.254.33.2",
                piaStage = LdnPiaStage.NET_PROBING,
                piaNetRequests = 4,
                piaSessionRequests = 2,
                piaSessionResponses = 1,
                piaDatagramsGenerated = 8,
                ethernetFramesSubmitted = 9,
                ethernetFramesAccepted = 9,
                ethernetCommandsWritten = 9,
                ethernetTxCompleted = 8,
                ethernetTxAcknowledged = 3,
                ethernetTxUnacknowledged = 5,
                firstEthernetTxAcknowledged = true,
            ),
        )

        assertTrue(diagnostics.contains("eth=3"))
        assertTrue(diagnostics.contains("piaNet=4"))
        assertTrue(diagnostics.contains("piaSessReq=2"))
        assertTrue(diagnostics.contains("piaSessRes=1"))
        assertTrue(diagnostics.contains("participantEver=true"))
        assertTrue(diagnostics.contains("regAdv=6"))
        assertTrue(diagnostics.contains("participantIndex=1"))
        assertTrue(diagnostics.contains("participantIp=169.254.33.2"))
        assertTrue(diagnostics.contains("piaDgram=8"))
        assertTrue(diagnostics.contains("ethSubmit=9"))
        assertTrue(diagnostics.contains("ethQueue=9"))
        assertTrue(diagnostics.contains("ethWrite=9"))
        assertTrue(diagnostics.contains("ethTxDone=8"))
        assertTrue(diagnostics.contains("ethAck=3"))
        assertTrue(diagnostics.contains("ethUnack=5"))
        assertTrue(diagnostics.contains("firstEthAck=true"))
    }
}
