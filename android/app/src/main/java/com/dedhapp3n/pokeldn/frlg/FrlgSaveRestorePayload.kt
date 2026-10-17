package com.dedhapp3n.pokeldn.frlg

internal object FrlgSaveRestorePayload {
    private const val PATCH_OFFSET = 8
    private val loadGameSave = mapOf(
        "BPRE" to 0x080ddbf4, "BPGE" to 0x080ddbc8,
        "BPRF" to 0x080ddfd4, "BPGF" to 0x080ddfa8,
        "BPRD" to 0x080ddf14, "BPGD" to 0x080ddee8,
        "BPRI" to 0x080ddf14, "BPGI" to 0x080ddee8,
        "BPRS" to 0x080ddffc, "BPGS" to 0x080ddfd0,
        "BPRJ" to 0x080ded68, "BPGJ" to 0x080ded3c,
    )
    val supportedGameCodes: Set<String> get() = loadGameSave.keys
    fun build(gameCode: String): ByteArray {
        val address = loadGameSave[gameCode] ?: throw IllegalArgumentException("Unsupported FRLG cartridge: $gameCode")
        return RAW.copyOf().also { putU32(it, PATCH_OFFSET, address or 1) }
    }
    fun loadGameSaveAddress(gameCode: String): Int = loadGameSave.getValue(gameCode)
    private fun putU32(out: ByteArray, at: Int, value: Int) = repeat(4) { out[at + it] = (value ushr (8 * it)).toByte() }
    private val RAW = hexRestore("""
010000ea270000ea0000000014104fe234229fe534329fe504c091e404c082e4043053e2fbffff1a2c229fe50030a0e3003082e5ff30a0e3043082e570402de90060a0e114429fe53c4086e5151ea0e3b413c6e10050a0e32522a0e16f0000eb0e34a0e30f1005e2013683e00f3c83e2f43083e20c20a0e30100d3e40100c4e4012052e2fbffff1a015085e21c0055e3f0ffff3a0020a0e3600000eb0100a0e37040bde81eff2fe1f0472de900a0a0e19c819fe50410d8e5020051e34300000a010051e34c00001a88619fe5017a86e2b810d8e1016086e00c4088e2ba10d8e1015084e0050054e11600002a0110d4e4800051e30800003a7d2041e20110d4e4023086e0070053e10c00008a0110c6e4012052e2fcffff1af1ffffea012081e2023086e0070053e10400008a0110d4e40110c6e4012052e2fbffff1ae8ffffea0211a0e31b0000ea0610d8e5010011e32900000a0590d8e50900a0e1f4109fe5b5308fe2270000eb2922a0e12b0000eb0e34a0e30f1009e2013683e0d4609fe5012aa0e30040a0e30100d3e40110d6e4010020e0004084e1012052e2f9ffff1a0020a0e31d0000eb000054e30500000a0110a0e31119a0e19c209fe5003092e5013083e1003082e58c409fe5070000ea84409fe5001094e5000051e30300001a0000a0e3e4311fe5060000eb040084e53c408ae50810a0e3b413cae10100a0e3f047bde81eff2fe101c08fe21cff2fe100b500f002f808bc1847184748df70473c009fe53cc09fe5aa10a0e30010c0e55510a0e30010cce5b010a0e30010c0e50e04a0e30020c0e51eff2fe100c401026c02000000c0010200c8010200d8010210d801025555000eaa2a000e
""".filterNot(Char::isWhitespace))
}
private fun hexRestore(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
