package com.dedhapp3n.pokeldn.ldn

enum class LdnApTestPhase(val label: String) {
    DISCONNECTED("Disconnected"),
    READY("Ready"),
    PREPARING("Preparing"),
    STARTING_AP("Starting AP"),
    ADVERTISING("Advertising"),
    CONSOLE_ACTIVITY("Console activity detected"),
    STOPPING("Stopping"),
    FAILED("Failed"),
    CLEANUP_REQUIRED("Cleanup required"),
}

data class LdnApTestState(
    val phase: LdnApTestPhase = LdnApTestPhase.DISCONNECTED,
    val detail: String? = null,
    val channel: Int? = null,
    val advertisementsSent: Long = 0,
    val discoveryActivityCount: Long = 0,
    val stationDetected: Boolean = false,
    val latestActivity: String? = null,
)
