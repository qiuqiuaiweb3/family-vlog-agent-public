package com.chill.familyvlog.ai

enum class AiFailureCode {
    INLINE_VIDEO_MIME_UNKNOWN,
    INLINE_VIDEO_MIME_UNSUPPORTED,
    INVALID_REQUEST_SHAPE,
    NO_CANDIDATE,
    ABNORMAL_FINISH,
    EMPTY_TEXT,
    SERVICE_ERROR,
}

class AiFailureException(val code: AiFailureCode) : RuntimeException(code.name)

interface OneShotModelClient {
    suspend fun generate(request: ModelRequest): AiRawResult
}
