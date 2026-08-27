package com.chill.familyvlog.ai

import java.nio.charset.StandardCharsets
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object InlineRequestBudget {
    const val REQUEST_LIMIT_BYTES = 20_000_000L
    const val FIXED_SDK_SCHEMA_WRAPPER_ALLOWANCE_BYTES = 1_000_000L

    fun base64EncodedLength(rawByteCount: Int): Long =
        4L * ((rawByteCount.toLong() + 2L) / 3L)

    fun estimateTotalBytes(
        rawBytes: ByteArray,
        systemPrompt: String,
        userTask: String,
        mimeType: String,
    ): Long = estimateTotalBytes(
        rawByteCount = rawBytes.size,
        systemPrompt = systemPrompt,
        userTask = userTask,
        mimeType = mimeType,
    )

    fun estimateTotalBytes(
        rawByteCount: Int,
        systemPrompt: String,
        userTask: String,
        mimeType: String,
    ): Long = FIXED_SDK_SCHEMA_WRAPPER_ALLOWANCE_BYTES +
        escapedJsonUtf8Length(systemPrompt) +
        escapedJsonUtf8Length(userTask) +
        escapedJsonUtf8Length(mimeType) +
        base64EncodedLength(rawByteCount)

    fun fits(
        rawBytes: ByteArray,
        systemPrompt: String,
        userTask: String,
        mimeType: String,
    ): Boolean = estimateTotalBytes(rawBytes, systemPrompt, userTask, mimeType) < REQUEST_LIMIT_BYTES

    fun fits(
        rawByteCount: Int,
        systemPrompt: String,
        userTask: String,
        mimeType: String,
    ): Boolean = estimateTotalBytes(rawByteCount, systemPrompt, userTask, mimeType) < REQUEST_LIMIT_BYTES

    fun maxInlineVideoBytes(
        systemPrompt: String,
        userTask: String,
        mimeType: String,
    ): Int {
        val availableBase64Bytes = REQUEST_LIMIT_BYTES -
            FIXED_SDK_SCHEMA_WRAPPER_ALLOWANCE_BYTES -
            escapedJsonUtf8Length(systemPrompt) -
            escapedJsonUtf8Length(userTask) -
            escapedJsonUtf8Length(mimeType) -
            1L
        val completeBase64Groups = availableBase64Bytes.coerceAtLeast(0L) / 4L
        return (completeBase64Groups * 3L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun escapedJsonUtf8Length(value: String): Long = Json.Default
        .encodeToString(String.serializer(), value)
        .toByteArray(StandardCharsets.UTF_8)
        .size
        .toLong()
}
