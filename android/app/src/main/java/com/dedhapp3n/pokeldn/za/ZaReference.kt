package com.dedhapp3n.pokeldn.za

import java.util.zip.CRC32

/** Upstream za/reference.py's safe name change and matching 1403 SyncDataSet. */
internal object ZaReference {
    fun namedIdentity(reference: ByteArray, name: String): ByteArray {
        ZaMessages.trainer(reference)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        require(nameBytes.isNotEmpty() && nameBytes.size <= 24) { "Z-A host name must fit twelve UTF-16 units" }
        return reference.copyOf().also {
            it.fill(0, 0x18, 0x18 + 26)
            nameBytes.copyInto(it, 0x18)
        }
    }

    fun syncMessage(identity: ByteArray): ByteArray {
        ZaMessages.trainer(identity)
        var hash = 0x811c9dc5L
        for (at in 0x0d until 0x0d + 0x5d) {
            hash = ((hash xor (identity[at].toInt() and 255).toLong()) * 0x01000193L) and 0xffffffffL
        }
        val word = byteArrayOf(hash.toByte(), (hash ushr 8).toByte(), (hash ushr 16).toByte(), (hash ushr 24).toByte())
        val crc = CRC32().apply { update(word) }.value
        return byteArrayOf(0x14, 0x03, 0xb9.toByte(), 1, 0x82.toByte(),
            crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte())
    }
}
