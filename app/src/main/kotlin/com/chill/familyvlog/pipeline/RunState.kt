package com.chill.familyvlog.pipeline

enum class RunPhase { PREPARING, ANALYZING, PLANNING, RENDERING, SAVING }

enum class RunFailureCode {
    INPUT_PREPARATION_FAILED,
    ANALYSIS_INPUT_TOO_LARGE,
    UNDERSTANDING_FAILED,
    EDITING_FAILED,
    PRIVATE_STORAGE_FAILED,
    PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED,
    RENDER_FAILED,
    OUTPUT_INSPECTION_FAILED,
    PUBLISH_FAILED,
}

sealed interface RunState {
    data object Idle : RunState
    data class Active(val phase: RunPhase) : RunState
    data object Succeeded : RunState
    data class Failed(val phase: RunPhase, val code: RunFailureCode) : RunState
    data object Cancelled : RunState
}

class PipelineException(
    val phase: RunPhase,
    val code: RunFailureCode,
) : RuntimeException("${phase.name}:${code.name}")
