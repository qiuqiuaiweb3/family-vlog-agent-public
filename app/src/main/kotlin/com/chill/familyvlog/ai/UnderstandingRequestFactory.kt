package com.chill.familyvlog.ai

import com.chill.familyvlog.contract.SourceWindow
import kotlinx.serialization.json.JsonObject

class UnderstandingRequestFactory(private val prompts: PromptRepository) {
    fun maxInlineVideoBytes(
        window: SourceWindow,
        sourceMetadata: JsonObject,
        inlineVideoMimeType: String,
    ): Int = InlineRequestBudget.maxInlineVideoBytes(
        systemPrompt = prompts.understandingSystemPrompt(),
        userTask = buildUnderstandingTask(window, sourceMetadata),
        mimeType = inlineVideoMimeType,
    )

    fun estimateTotalBytes(
        window: SourceWindow,
        sourceMetadata: JsonObject,
        bytes: ByteArray,
        inlineVideoMimeType: String,
    ): Long = InlineRequestBudget.estimateTotalBytes(
        rawBytes = bytes,
        systemPrompt = prompts.understandingSystemPrompt(),
        userTask = buildUnderstandingTask(window, sourceMetadata),
        mimeType = inlineVideoMimeType,
    )

    fun create(
        window: SourceWindow,
        sourceMetadata: JsonObject,
        bytes: ByteArray,
        inlineVideoMimeType: String,
    ): ModelRequest = ModelRequest(
        systemPrompt = prompts.understandingSystemPrompt(),
        schema = understandingResponseSchema(),
        parts = listOf(
            ModelPart.InlineVideo(bytes, inlineVideoMimeType),
            ModelPart.Text(buildUnderstandingTask(window, sourceMetadata)),
        ),
    )
}
