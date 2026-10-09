package com.dedhapp3n.pokeldn.frlg

enum class FrlgOperationPhase(val label: String) {
    IDLE("Ready"),
    PREPARING_ESP32("Preparing ESP32"),
    STARTING_NETWORK("Starting wireless network"),
    WAITING_FOR_CONSOLE("Waiting for console"),
    CONSOLE_DETECTED("Console detected"),
    ESTABLISHING_GAME_LINK("Establishing game link"),
    SENDING_BOOST("Sending boost"),
    WAITING_FOR_CLOSE("Waiting for save/close"),
    RETURNING_TO_READY("Returning adapter to Ready"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class FrlgOperationState(
    val phase: FrlgOperationPhase = FrlgOperationPhase.IDLE,
    val detail: String? = null,
) {
    val running: Boolean get() = phase in FrlgOperationPhase.PREPARING_ESP32..FrlgOperationPhase.RETURNING_TO_READY
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
        require(phase in FrlgOperationPhase.STARTING_NETWORK..FrlgOperationPhase.WAITING_FOR_CLOSE) {
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
