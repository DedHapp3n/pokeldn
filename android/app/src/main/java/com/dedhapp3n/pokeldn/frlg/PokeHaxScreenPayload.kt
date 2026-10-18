package com.dedhapp3n.pokeldn.frlg

object PokeHaxScreenPreset {
    const val OPERATION_ID = "frlg-pokehax-screen"
    const val DISPLAY_TEXT = "POKEHAX"
}

internal data class FrlgBufferTransfer(
    val leads: List<ByteArray>,
    val payload: ByteArray,
)

/** Multi-stage RAM-only form of [asm/pokehax-screen.s]. */
internal object PokeHaxScreenPayload {
    private const val BODY_ADDRESS = 0x0201c800
    private const val CHUNK_BYTES = 976

    private data class Layout(val oam: Int, val palette: Int, val rng: Int)

    private val baseBody = (
        "01308fe213ff2fe1f0b5071c454e3968454a91424ad0454c2588ed43c3242540356000257560b560f56035611436404c2588356004363f4c023c2568" +
        "3560043665683560043604231b02e41a256835600436656835600436364c023cf8239b00e41a25883560043604231b02e41a258835600436304c0f23" +
        "9b01e41a80256d002368336004340436013df9d12b4cf0252368336004340436013df9d100f096f8204e2149396000f0cdf836e0f5680135f5601e4c" +
        "2588ed43c324254033682c1c9c4335607568002d10d002231c4202d0002575601fe0032d1dd101231c421ad03569013503231d40356114e0b5684023" +
        "1c4202d0013d03231d4080231c4202d0013503231d40b56001231c4203d0032d16d00135756000f095f80020f0bc02bc0847000000e001024d584850" +
        "3001000400000004d6790302c8260003007c01061436326804361d4c023c35682560043635686560043604231b02e41a356825600436356865600436" +
        "144c023cf8239b00e41a35682580043604231b02e41a3568258004360e4c0f239b01e41a80256d003368236004360434013df9d1094cf02533682360" +
        "04360434013df9d1064c228001203860f0bc02bc08470000d6790302c8260003007c010600000004f0b5184c002695a3321c5200995a351c6d012d19" +
        "00271448012f17d3062f15d23a1c013a131c5200d2180b1cd3400122134201d00d4a801a0222134201d00c4a801a0422134201d00a4a801a3a1c9200" +
        "a8500137082fded301361e2ed3d3f0bc01bc0047007c010622222222000100000010000000000100f0b54e4e4e4c4f4d2580356990a46d00675b4d4c" +
        "023c27804c4d6580a78004231b02e41a27806580a780474c023cf8239b00e41a278004231b02e41a2780444c0f239b01e41a8025a026002026806080" +
        "60600834013df9d13d4c8025e580e081e082e5836ea300255e5d394c0f229201a41af600a419c426b60026802e1c36014036f022920116436680324e" +
        "76190136a6800135072de7d3284e7468002c23d150205821382260a300f058f8552048225da3063300f052f8582058225aa30a3300f04cf85d206822" +
        "57a3103300f046f84f204821194eb5682a1c1201383252a3153300f03bf826e0012c01d14ea306e0022c02d14ca3063301e04ba30a33502040213822" +
        "00f02af80c4e7468012c01d1f0680be0022c02d10f4d286806e030695a216022482300f049f804e05a216022482300f027f8f0bc01bc004700e00102" +
        "0000000440100000d6790302ff7f0000c8260003e0f30000204200031d780133002d0ed03c4c0f26b601a41b061cf600a41922806180394e7619a680" +
        "01300831ece77047f0b52ea41c25061cee400f273e40a65d304f0f24a4013f1b0c1ce4003f193b807a802d4c3619be8024a401310832043de9d5f0bc" +
        "01bc0047f0b50f2420401fa4245c244f0f26b601bf1bc9007f183b807a80214d2d19bd80f0bc01bc0047c0460000ef136f7bed5acf72ed5bea5bad5a" +
        "cf12eb5afd5bfd5f4e6b4f724972977497246f7b9a74e773a779ed49cf79cf7b2749ef7bef791115eb3a6b3b78797a7b7c7f7e000809060a0400090b" +
        "0c000d020e02090004070f10001b00001112131415161718191a061c0d1d040800000c0080010030c8260003e0f30000"
    ).hexBytesScreen()

    private val copyHeader = (
        "010000ea00000000000000001c308fe214201fe514101fe5041051e2010093e7" +
        "010082e7fbffff1a0100a0e31eff2fe1"
    ).hexBytesScreen()
    private val trampoline = "00309fe513ff2fe100c80102".hexBytesScreen()

    private val layouts = mapOf(
        "BPRF" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPGF" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPRE" to Layout(0x03002778, 0x020379d6, 0x030042d0),
        "BPGE" to Layout(0x03002778, 0x020379d6, 0x030042d0),
        "BPRS" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPGS" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPRD" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPGD" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPRI" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPGI" to Layout(0x030026c8, 0x020379d6, 0x03004220),
        "BPRJ" to Layout(0x030026d8, 0x0203790a, 0x03004230),
        "BPGJ" to Layout(0x030026d8, 0x0203790a, 0x03004230),
    )

    fun forGameCode(code: String): FrlgBufferTransfer? = layouts[code]?.let { layout ->
        val body = baseBody.copyOf().also {
            it.replaceWords(0x030026c8, layout.oam)
            it.replaceWords(0x020379d6, layout.palette)
            it.replaceWords(0x03004220, layout.rng)
        }
        val leads = body.asList().chunked(CHUNK_BYTES).mapIndexed { index, bytes ->
            copyHeader.copyOf().also {
                it.putLe32Screen(4, BODY_ADDRESS + index * CHUNK_BYTES)
                it.putLe32Screen(8, bytes.size)
            } + bytes.toByteArray()
        }
        require(leads.all { it.size <= 1024 } && trampoline.size <= 1024)
        FrlgBufferTransfer(leads, trampoline.copyOf())
    }
}

private fun String.hexBytesScreen() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.putLe32Screen(offset: Int, value: Int) {
    repeat(4) { this[offset + it] = (value ushr (8 * it)).toByte() }
}
private fun ByteArray.replaceWords(old: Int, new: Int) {
    val needle = ByteArray(4).also { it.putLe32Screen(0, old) }
    var at = 0
    while (at <= size - 4) {
        if ((0 until 4).all { this[at + it] == needle[it] }) {
            putLe32Screen(at, new)
            at += 4
        } else at++
    }
}
