package com.dedhapp3n.pokeldn.ldn

enum class LdnApTestPhase(val label: String) {
    DISCONNECTED("Disconnected"),
    READY("Ready"),
    PREPARING("Preparing"),
    STARTING_AP("Starting AP"),
    AP_ACTIVE("AP active"),
    STOPPING("Stopping"),
    FAILED("Failed"),
    CLEANUP_REQUIRED("Cleanup required"),
}

data class LdnApTestState(
    val phase: LdnApTestPhase = LdnApTestPhase.DISCONNECTED,
    val detail: String? = null,
)
