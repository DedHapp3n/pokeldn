package com.dedhapp3n.pokeldn.za

import java.security.MessageDigest

internal data class ZaRawSaveValidation(
    val byteLength: Int,
    val knownSize: Boolean,
    val hashValid: Boolean,
    val nonUniform: Boolean,
) {
    val valid: Boolean get() = knownSize && hashValid && nonUniform
}

/** Validates the encrypted raw save format accepted by PKHeX's SAV9ZA constructor. */
internal object ZaRawSaveValidator {
    val knownSizes = setOf(0x2F3284, 0x2F3289, 0x309FA6, 0x309FB3)

    private val introHashBytes = hex(
        "9EC99CD70ED33C44FB9303DCEB39B42A1947E9634BA2334416BF82A2BA6355B6" +
            "3D9DF24B5F7B6AB2621DC21B68E5C8B53A059000E8A8103DE2ECF00CB2ED4F6D",
    )
    private val outroHashBytes = hex(
        "D6C01C598BC8B8CB46E153FC828C757513E045DF32693C75F059F8D9A25FB217" +
            "E08052DBEA8973997579AFCB2E8007E6F126E0030AE66FF641BF7E59C2AE55FD",
    )

    fun validate(data: ByteArray): ZaRawSaveValidation {
        val hashValid = if (data.size >= 32) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(introHashBytes)
            digest.update(data, 0, data.size - 32)
            digest.update(outroHashBytes)
            MessageDigest.isEqual(digest.digest(), data.copyOfRange(data.size - 32, data.size))
        } else false
        val nonUniform = data.isNotEmpty() && data.any { it != data[0] }
        return ZaRawSaveValidation(data.size, data.size in knownSizes, hashValid, nonUniform)
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
