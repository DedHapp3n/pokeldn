package com.dedhapp3n.pokeldn.frlg

object PokeHaxScreenPreset {
    const val OPERATION_ID = "frlg-pokehax-screen"
    const val DISPLAY_TEXT = "POKEHAX"
}

/**
 * Prebuilt [asm/pokehax-screen.s] ARM payload.
 *
 * It uses only RAM and GBA display memory/registers. The touched OBJ tiles, OAM entries,
 * palettes, and display control are restored after A is pressed. OAM/palette addresses are
 * patched to the same version-specific game buffers used by the resident overlay.
 */
internal object PokeHaxScreenPayload {
    private const val OAM_LITERAL_OFFSET = 760
    private const val PALETTE_LITERAL_OFFSET = 764

    private val baseImage = (
        "f04f2de900a0a0e100109ae5d0229fe5020051e12700000ac8229fe5020051e12c00000a2cb04fe201bb8be2b8429fe5b050d4e104508be4bc429fe5" +
        "025044e2006095e504608be4015b45e2006095e504608be4a4629fe5065044e0b060d5e104608be4015b45e2b060d5e104608be480429fe50f4d44e2" +
        "015ca0e3046094e404608be4015055e2fbffff1a60429fe5e050a0e3046094e404608be4015055e2fbffff1a370000eb5f0000eb30129fe500108ae5" +
        "300000ea5b0000eb2c429fe5b050d4e1010015e32b00000a14129fe500108ae5280000ea530000eb0c429fe5b050d4e1010015e32300001af4b04fe2" +
        "01bb8be204809be404909be404709be404609be404509be4e8419fe50f4d44e2013ca0e304209be4042084e4013053e2fbffff1ac8419fe5e030a0e3" +
        "04209be4042084e4013053e2fbffff1ab8419fe5023044e2009083e5013b43e2007083e5a8319fe5034044e0b060c4e1014b44e2b050c4e180419fe5" +
        "b080c4e10100a0e300008ae5f04fbde81eff2fe10000a0e3f04fbde81eff2fe164419fe5059d8fe20060a0e3862089e0b080d2e1865384e00070a0e3" +
        "64019fe50010a0e1030057e30f00003a0d0057e30d00002a032047e2a220a0e1822082e03832a0e1010013e33c219f1502004010020013e334219f15" +
        "0200401001104112040013e328219f1502104110080057e307218530082047220221852040208222000082e5201082e5017087e2100057e3e1ffff3a" +
        "016086e2070056e3daffff3a1eff2fe1b8409fe5cc509fe5b050c4e1bc409fe5c4509fe5025004e5016b44e2025006e5ac609fe5064044e00050a0e3" +
        "b050c4e1014b44e2b050c4e18c409fe50f4d44e28050a0e3a060a0e30070a0e3b060c4e1b270c4e1047084e5084084e2015055e2f9ffff1a60409fe5" +
        "0050a0e34860a0e3b060c4e10562a0e1406086e2016986e3b260c4e154609fe5056186e0b460c4e1b670c4e1084084e2015085e2070055e3f1ffff3a" +
        "1eff2fe1ef136f7bed5acf72ed5bea5bad5a000001584850025848500000000430010004807c0106c8260003d6790302e2030000401000000000ff7f" +
        "e4f3000022222222000010010000001010010000"
    ).hexBytesScreen()

    private val layouts = mapOf(
        "BPRF" to (0x030026c8 to 0x020379d6), "BPGF" to (0x030026c8 to 0x020379d6),
        "BPRE" to (0x03002778 to 0x020379d6), "BPGE" to (0x03002778 to 0x020379d6),
        "BPRS" to (0x030026c8 to 0x020379d6), "BPGS" to (0x030026c8 to 0x020379d6),
        "BPRD" to (0x030026c8 to 0x020379d6), "BPGD" to (0x030026c8 to 0x020379d6),
        "BPRI" to (0x030026c8 to 0x020379d6), "BPGI" to (0x030026c8 to 0x020379d6),
        "BPRJ" to (0x030026d8 to 0x0203790a), "BPGJ" to (0x030026d8 to 0x0203790a),
    )

    fun forGameCode(code: String): ByteArray? = layouts[code]?.let { (oam, palette) ->
        baseImage.copyOf().also {
            it.putLe32Screen(OAM_LITERAL_OFFSET, oam)
            it.putLe32Screen(PALETTE_LITERAL_OFFSET, palette)
        }
    }
}

private fun String.hexBytesScreen() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.putLe32Screen(offset: Int, value: Int) {
    repeat(4) { this[offset + it] = (value ushr (8 * it)).toByte() }
}
