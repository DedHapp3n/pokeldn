package com.dedhapp3n.pokeldn.frlg

import java.io.File

/** App-private last-run trace. A new restore replaces it; cleanup deliberately does not erase it. */
internal class FrlgRestoreTraceStore(private val file: File) {
    fun replace(trace: String) {
        if (trace.isBlank()) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(trace)
        if (!temporary.renameTo(file)) {
            file.writeText(trace)
            temporary.delete()
        }
    }

    fun load(): String? = runCatching { file.takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank) }
        .getOrNull()

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    companion object {
        const val FILE_NAME = "frlg-last-restore-trace.txt"
    }
}
