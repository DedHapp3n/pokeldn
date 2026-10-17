package com.dedhapp3n.pokeldn.frlg

data class FrlgSaveRestoreUiState(
    val operation: FrlgOperationState = FrlgOperationState(),
    val filename: String? = null,
    val fileSize: Int = 0,
    val save: ByteArray? = null,
    val hasWholeSlot: Boolean = false,
    val validationDetail: String? = null,
    val cartridge: String? = null,
    val sectorsWritten: Int = 0,
    val totalSectors: Int = 0,
    val failureMask: Long? = null,
    val loadResult: Int? = null,
) { val readyFile get() = save?.size == FrlgSave.SAVE_SIZE && hasWholeSlot; val running get()=operation.running }
