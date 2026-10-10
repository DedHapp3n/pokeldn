package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.esp32.formatMac
import com.dedhapp3n.pokeldn.ldn.LdnDiscoverySnapshot

enum class FrlgOperationPhase(val label: String) {
    IDLE("Ready"),
    PREPARING_ESP32("Preparing ESP32"),
    STARTING_NETWORK("Starting wireless network"),
    WAITING_FOR_CONSOLE("Waiting for console"),
    CONSOLE_DETECTED("Console detected"),
    STATION_ASSOCIATED("Station associated"),
    AUTHENTICATING("Authenticating"),
    PARTICIPANT_REGISTERED("Participant registered"),
    ESTABLISHING_PIA("Establishing PIA session"),
    ESTABLISHING_RELIABLE("Establishing reliable link"),
    ESTABLISHING_RFU("Establishing RFU"),
    ESTABLISHING_GAME_LINK("Establishing game link"),
    READING_CARTRIDGE("Reading cartridge"),
    PREPARING_BOOST("Preparing boost"),
    SENDING_BOOST("Sending boost"),
    WAITING_FOR_CLOSE("Waiting for save/close"),
    CLOSING_LINK("Closing link"),
    RETURNING_TO_READY("Returning adapter to Ready"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class FrlgOperationState(
    val phase: FrlgOperationPhase = FrlgOperationPhase.IDLE,
    val detail: String? = null,
    val lastReached: FrlgOperationPhase? = null,
    val diagnostics: String? = null,
) {
    val running: Boolean get() = phase in FrlgOperationPhase.PREPARING_ESP32..FrlgOperationPhase.RETURNING_TO_READY

    fun recordProgress(next: FrlgOperationState): FrlgOperationState {
        val previousRank = lastReached?.progressRank() ?: -1
        val nextRank = next.lastReached?.progressRank() ?: -1
        return next.copy(
            lastReached = if (nextRank >= previousRank) next.lastReached else lastReached,
            diagnostics = next.diagnostics ?: diagnostics,
        )
    }
}

data class FrlgStartAvailability(val enabled: Boolean, val reason: String? = null)

object FrlgOperationStartGate {
    fun evaluate(
        keysAvailable: Boolean,
        handshakeVerified: Boolean,
        radioReady: Boolean,
        frlgOperationOwned: Boolean,
        otherRadioOperationOwned: Boolean,
        radioDetail: String? = null,
    ): FrlgStartAvailability = when {
        frlgOperationOwned -> FrlgStartAvailability(false, "A Walk Through Walls operation is still active or cleaning up")
        otherRadioOperationOwned -> FrlgStartAvailability(false, "Diagnostics currently owns the ESP32 radio")
        !keysAvailable -> FrlgStartAvailability(false, "Valid prod.keys are required")
        !handshakeVerified -> FrlgStartAvailability(false, "ESP32 verification is required")
        !radioReady -> FrlgStartAvailability(false, radioDetail ?: "ESP32 radio is not Ready")
        else -> FrlgStartAvailability(true)
    }
}

private fun FrlgOperationPhase.progressRank(): Int = when (this) {
    FrlgOperationPhase.WAITING_FOR_CONSOLE -> 0
    FrlgOperationPhase.CONSOLE_DETECTED -> 1
    FrlgOperationPhase.STATION_ASSOCIATED -> 2
    FrlgOperationPhase.AUTHENTICATING -> 3
    FrlgOperationPhase.PARTICIPANT_REGISTERED -> 4
    FrlgOperationPhase.ESTABLISHING_PIA -> 5
    FrlgOperationPhase.ESTABLISHING_RELIABLE -> 6
    FrlgOperationPhase.ESTABLISHING_RFU -> 7
    FrlgOperationPhase.ESTABLISHING_GAME_LINK -> 8
    FrlgOperationPhase.READING_CARTRIDGE -> 9
    FrlgOperationPhase.PREPARING_BOOST -> 10
    FrlgOperationPhase.SENDING_BOOST -> 11
    FrlgOperationPhase.WAITING_FOR_CLOSE -> 12
    FrlgOperationPhase.CLOSING_LINK -> 13
    else -> -1
}

internal fun frlgFailureDiagnostics(snapshot: LdnDiscoverySnapshot): String = buildString {
    append("Counters: discovery=${snapshot.discoveryActivityCount}")
    append(" station=${snapshot.stationJoins}/${snapshot.stationLeaves}")
    append(" auth=${snapshot.authenticationRequests}/${snapshot.authenticationResponses}")
    append(" participant=${snapshot.participantRegistered}")
    append(" participantEver=${snapshot.participantEverRegistered}")
    append(" regAdv=${snapshot.registeredAdvertisementsSent}")
    append(" participantIndex=${snapshot.participantIndex ?: "unknown"}")
    append(" participantIp=${snapshot.participantIp ?: "unknown"}")
    append(" eth=${snapshot.ethernetFrames}")
    append(" PIA=${snapshot.piaStage}")
    append(" piaNet=${snapshot.piaNetRequests}")
    append(" piaSessReq=${snapshot.piaSessionRequests}")
    append(" piaSessRes=${snapshot.piaSessionResponses}")
    append(" piaDgram=${snapshot.piaDatagramsGenerated}")
    append(" ethSubmit=${snapshot.ethernetFramesSubmitted}")
    append(" ethQueue=${snapshot.ethernetFramesAccepted}")
    append(" ethWrite=${snapshot.ethernetCommandsWritten}")
    append(" ethTxDone=${snapshot.ethernetTxCompleted}")
    append(" ethAck=${snapshot.ethernetTxAcknowledged}")
    append(" ethUnack=${snapshot.ethernetTxUnacknowledged}")
    append(" firstEthAck=${snapshot.firstEthernetTxAcknowledged ?: "unknown"}")
    append(" rawData=${snapshot.rawDataTraces}")
    append(" rawDataAfterReg=${snapshot.rawDataAfterRegistration}")
    append(" rawPeerData=${snapshot.rawPeerDataAfterRegistration}")
    append(" rawProtected=${snapshot.rawProtectedPeerDataAfterRegistration}")
    append(" rawDecoded=${snapshot.rawDecoded}")
    append(" rawDecryptFail=${snapshot.rawDecryptFailures}")
    append(" arpRx=${snapshot.arpFramesReceived}")
    append(" arpReply=${snapshot.arpRepliesSent}")
    snapshot.firstPeerDataTrace?.let { trace ->
        append(" rawFirst=")
        append("toDS:${if (trace.toDs) 1 else 0}")
        append("/fromDS:${if (trace.fromDs) 1 else 0}")
        append("/protected:${if (trace.protectedFrame) 1 else 0}")
        append("/src:${trace.sourceMac.formatMac()}")
        append("/target:${trace.targetMac.formatMac()}")
        append("/len:${trace.frameLength}")
        trace.ccmpKeyId?.let { append("/keyId:$it") }
    }
    append(" Reliable=${snapshot.reliableFramesReceived}/${snapshot.reliableFramesSent}")
    append(" RFU=${snapshot.rfuConnected}")
    append(" LinkPlayer=${snapshot.linkPlayerExchanged}")
    snapshot.detectedCartridge?.let { append(" cartridge=$it") }
    snapshot.giftStage?.let { append(" gift=$it") }
}

/** Pure lifecycle gate used by the future LDN operation runner. */
class FrlgOperationLifecycle {
    var state = FrlgOperationState()
        private set

    fun begin() {
        check(!state.running) { "An FRLG operation is already running" }
        state = FrlgOperationState(FrlgOperationPhase.PREPARING_ESP32)
    }

    fun advance(phase: FrlgOperationPhase, detail: String? = null) {
        check(state.running) { "No FRLG operation is running" }
        require(phase in FrlgOperationPhase.STARTING_NETWORK..FrlgOperationPhase.CLOSING_LINK) {
            "Only active operation phases may advance directly"
        }
        state = FrlgOperationState(phase, detail)
    }

    fun beginCleanup(detail: String? = null) {
        check(state.running) { "No FRLG operation is running" }
        state = FrlgOperationState(FrlgOperationPhase.RETURNING_TO_READY, detail)
    }

    fun adapterReady(terminal: FrlgOperationPhase, detail: String? = null) {
        require(terminal in setOf(FrlgOperationPhase.COMPLETED, FrlgOperationPhase.FAILED, FrlgOperationPhase.CANCELLED))
        check(state.phase == FrlgOperationPhase.RETURNING_TO_READY) {
            "Adapter cannot become ready before cleanup"
        }
        state = FrlgOperationState(terminal, detail)
    }
}
