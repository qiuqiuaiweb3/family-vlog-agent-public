package com.chill.familyvlog.pipeline

import java.io.File

interface RunStore {
    fun saveFinalJson(understandingJson: String, editPlanJson: String): File
}

internal class RunStoreException(val code: RunFailureCode) : RuntimeException(code.name) {
    init {
        require(
            code == RunFailureCode.PRIVATE_STORAGE_FAILED ||
                code == RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED,
        )
    }
}
