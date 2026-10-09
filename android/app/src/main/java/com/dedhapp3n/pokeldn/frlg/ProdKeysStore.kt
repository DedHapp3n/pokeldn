package com.dedhapp3n.pokeldn.frlg

import android.content.Context
import com.dedhapp3n.pokeldn.ldn.LdnKeyException
import com.dedhapp3n.pokeldn.ldn.LdnProdKeys
import com.dedhapp3n.pokeldn.ldn.LdnProdKeysParser
import java.io.ByteArrayOutputStream
import java.io.InputStream

enum class ProdKeysPhase { MISSING, AVAILABLE, INVALID }

data class ProdKeysState(
    val phase: ProdKeysPhase = ProdKeysPhase.MISSING,
    val detail: String? = null,
)

class ProdKeysStore(private val context: Context) {
    private val file get() = context.filesDir.resolve(FILE_NAME)

    fun state(): ProdKeysState = if (!file.isFile) ProdKeysState() else validate(file.readBytes())

    fun importKeys(input: InputStream): ProdKeysState {
        val bytes = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BYTES) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size > MAX_BYTES) return ProdKeysState(ProdKeysPhase.INVALID, "prod.keys is unexpectedly large")
        val state = validate(bytes)
        if (state.phase == ProdKeysPhase.AVAILABLE) file.writeBytes(bytes)
        return state
    }

    fun loadLdnKeys(): LdnProdKeys {
        if (!file.isFile) throw LdnKeyException("prod.keys has not been imported")
        return LdnProdKeysParser.parse(file.readBytes())
    }

    private fun validate(bytes: ByteArray): ProdKeysState = ProdKeysValidator.validate(bytes)

    companion object {
        private const val FILE_NAME = "prod.keys"
        private const val MAX_BYTES = 1024 * 1024
    }
}

internal object ProdKeysValidator {
    fun validate(bytes: ByteArray): ProdKeysState {
        return try {
            LdnProdKeysParser.parse(bytes)
            ProdKeysState(ProdKeysPhase.AVAILABLE, "Required LDN keys are available")
        } catch (error: LdnKeyException) {
            ProdKeysState(ProdKeysPhase.INVALID, error.message)
        }
    }

    internal val requiredKeys = LdnProdKeysParser.requiredKeyNames
}
