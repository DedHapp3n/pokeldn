package com.dedhapp3n.pokeldn.ldn

enum class LdnApTestPhase(val label: String) {
    DISCONNECTED("Disconnected"),
    READY("Ready"),
    PREPARING("Preparing"),
    STARTING_AP("Starting AP"),
    ADVERTISING("Advertising"),
    CONSOLE_ACTIVITY("Console activity detected"),
    AUTH_REQUEST("Authentication request received"),
    AUTH_RESPONSE_SENT("Authentication response sent"),
    AUTHENTICATION_REJECTED("Authentication rejected"),
    PARTICIPANT_REGISTERED("Participant registered"),
    STATION_ASSOCIATED("Station associated"),
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
    val authenticationRequests: Long = 0,
    val authenticationResponses: Long = 0,
    val authenticationFailures: Long = 0,
    val participantRegistered: Boolean = false,
    val latestActivity: String? = null,
)
