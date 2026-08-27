package com.chill.familyvlog.ai

class EditingRequestFactory(private val prompts: PromptRepository) {
    fun create(understandingJson: String): ModelRequest = ModelRequest(
        systemPrompt = prompts.editorSystemPrompt(),
        schema = editPlanResponseSchema(),
        parts = listOf(ModelPart.Text(buildEditingTask(understandingJson))),
    )
}
