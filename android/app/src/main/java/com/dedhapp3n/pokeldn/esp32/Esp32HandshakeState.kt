package com.dedhapp3n.pokeldn.esp32

enum class Esp32HandshakePhase(val label: String) {
    IDLE("Not tested"),
    TESTING("Testing ESP32 protocol"),
    VERIFIED("ESP32 protocol verified"),
    FAILED("ESP32 handshake failed"),
}

data class Esp32HandshakeState(
    val phase: Esp32HandshakePhase = Esp32HandshakePhase.IDLE,
    val info: Esp32Info? = null,
    val detail: String? = null,
)
