package com.chill.familyvlog.ai

import com.google.firebase.ai.type.Schema

sealed interface ModelPart {
    data class InlineVideo(val bytes: ByteArray, val mimeType: String) : ModelPart

    data class Text(val value: String) : ModelPart
}

data class ModelRequest(
    val systemPrompt: String,
    val schema: Schema,
    val parts: List<ModelPart>,
)

data class AiRawResult(val text: String, val modelVersion: String?)
