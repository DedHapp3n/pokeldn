package com.dedhapp3n.pokeldn.frlg

/** Exact current upstream asm/save-backup.s binary and per-cartridge gRfu send-queue patches. */
internal object FrlgSaveBackupPayload {
    private const val FIRST_OFFSET = 0x004
    private const val SEND_QUEUE_OFFSET = 0x008

    val supportedGameCodes: Set<String> get() = sendQueues.keys

    private val sendQueues = linkedMapOf(
        "BPRE" to 0x03005e62, "BPGE" to 0x03005e62,
        "BPRF" to 0x03005db2, "BPGF" to 0x03005db2,
        "BPRD" to 0x03005db2, "BPGD" to 0x03005db2,
        "BPRI" to 0x03005db2, "BPGI" to 0x03005db2,
        "BPRS" to 0x03005db2, "BPGS" to 0x03005db2,
        "BPRJ" to 0x03005df2, "BPGJ" to 0x03005df2,
    )

    fun build(gameCode: String, first: Int = 0): ByteArray {
        require(first in 0..FrlgSave.SAVE_SIZE) { "Invalid backup start offset $first" }
        val queue = sendQueues[gameCode.uppercase()]
            ?: throw IllegalArgumentException("Unsupported FRLG game code: $gameCode")
        return unpatched.copyOf().also {
            putU32(it, FIRST_OFFSET, first)
            putU32(it, SEND_QUEUE_OFFSET, queue)
        }
    }

    private fun putU32(data: ByteArray, at: Int, value: Int) {
        repeat(4) { i -> data[at + i] = (value ushr (8 * i)).toByte() }
    }

    private val unpatched = hex(
        "010000ea00000000000000000c201fe50020d2e5000052e30000a0131eff2f11f0472de9fc919fe5001090e5212ca0e15a0052e338101f150030a01300308915ff14c1e3020851e36300002a2138a0e1013083e20338a0e1015043e0020a55e3025aa08300a099e505005ae11700002a0a6045e0020b56e3026ba0832128a0e15a0000eb0e34a0e301c8a0e12c3883e00a3083e088c19fe50ac08ce006a08ae000a089e50170d3e40170cce4016056e2fbffff1a0020a0e34c0000eb5a1481e3001080e50000a0e3f047bde81eff2fe10030a0e3003089e540619fe53c6080e5017b86e238419fe5055084e0048041e0050054e13400002a0090d4e50120a0e3023084e0050053e10500002a00a0d3e509005ae10200001a012082e2820052e3f6ffff3a030052e30800003a023086e2070053e12400008a033042e2803083e30130c6e40190c6e4024084e0e7ffffea063047e0013053e21b00009a800053e38030a0830020a0e301a086e202c084e005005ce10f00002a000052e30800000a02e08ce205005ee10500002a0090dce501e0dce509005ee102e0dc0509005e010400000a0090dce50190cae4012082e2030052e1ecffff3a013042e20030c6e50a60a0e1024084e0c8ffffea081084e048309fe5033046e0b433c0e15a1481e3001080e50100a0e3f047bde81eff2fe104e02de530309fe530c09fe5aae0a0e300e0c3e555e0a0e300e0cce5b0e0a0e300e0c3e50e34a0e30020c3e504f09de400c4010200c8010200e801025555000eaa2a000e"
    )

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
