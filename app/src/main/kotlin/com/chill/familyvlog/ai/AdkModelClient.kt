package com.chill.familyvlog.ai

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.firebase.ai.type.Schema
import kotlinx.coroutines.flow.toList

internal enum class AdkAgentRole(val agentName: String) {
    VIDEO_UNDERSTANDING("video_understanding_agent"),
    STORY_PLANNING("story_planning_agent"),
}

internal fun interface AdkModelFactory {
    fun create(role: AdkAgentRole, responseSchema: Schema): Model
}

internal fun interface AdkOneShotInvocationFactory {
    fun create(
        role: AdkAgentRole,
        request: ModelRequest,
        model: Model,
    ): AdkOneShotInvocation
}

internal class AdkOneShotInvocation(
    val agent: LlmAgent,
    val runner: InMemoryRunner,
    val newMessage: Content,
    val runConfig: RunConfig,
) {
    suspend fun execute(): AiRawResult {
        val events = try {
            runner.runAsync(
                userId = LOCAL_USER_ID,
                sessionId = ONE_SHOT_SESSION_ID,
                newMessage = newMessage,
                runConfig = runConfig,
            ).toList()
        } finally {
            runner.close()
        }
        val response = events.last { event ->
            event.author == agent.name && event.content?.parts?.any { it.text != null } == true
        }
        val text = response.content!!.parts.joinToString(separator = "") { it.text.orEmpty() }
        return AiRawResult(text, response.modelVersion)
    }

    private companion object {
        const val LOCAL_USER_ID = "local_user"
        const val ONE_SHOT_SESSION_ID = "one_shot"
    }
}

class AdkModelClient internal constructor(
    private val modelFactory: AdkModelFactory,
    private val invocationFactory: AdkOneShotInvocationFactory,
) : OneShotModelClient {
    constructor() : this(
        modelFactory = FirebaseAdkModelFactory,
        invocationFactory = AdkOneShotInvocationFactory(::createAdkOneShotInvocation),
    )

    override suspend fun generate(request: ModelRequest): AiRawResult {
        val role = request.agentRole()
        val model = modelFactory.create(role, request.schema)
        return invocationFactory.create(role, request, model).execute()
    }

    private fun ModelRequest.agentRole(): AdkAgentRole = when {
        parts.size == 1 && parts[0] is ModelPart.Text -> AdkAgentRole.STORY_PLANNING
        parts.size == 2 &&
            parts[0] is ModelPart.InlineVideo &&
            parts[1] is ModelPart.Text -> AdkAgentRole.VIDEO_UNDERSTANDING
        else -> throw AiFailureException(AiFailureCode.INVALID_REQUEST_SHAPE)
    }
}

internal fun createAdkOneShotInvocation(
    role: AdkAgentRole,
    request: ModelRequest,
    model: Model,
): AdkOneShotInvocation {
    val agent = LlmAgent(
        name = role.agentName,
        model = model,
        subAgents = emptyList(),
        tools = emptyList(),
        toolsets = emptyList(),
        instruction = null,
        staticInstruction = Content.fromText(Role.SYSTEM, request.systemPrompt),
        outputSchema = null,
        outputKey = null,
        includeContents = LlmAgent.IncludeContents.NONE,
    )
    val sessionService = InMemorySessionService()
    val runner = InMemoryRunner(
        agent = agent,
        appName = "family_vlog_one_shot",
        sessionService = sessionService,
        artifactService = null,
        memoryService = null,
        plugins = emptyList(),
    )
    val newMessage = Content(
        role = Role.USER,
        parts = request.parts.map { part ->
            when (part) {
                is ModelPart.InlineVideo -> Part(
                    inlineData = Blob(
                        mimeType = part.mimeType,
                        data = part.bytes,
                    ),
                )
                is ModelPart.Text -> Part(text = part.value)
            }
        },
    )
    return AdkOneShotInvocation(
        agent = agent,
        runner = runner,
        newMessage = newMessage,
        runConfig = RunConfig(
            streamingMode = StreamingMode.NONE,
            maxLlmCalls = 1,
        ),
    )
}
