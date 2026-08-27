package com.chill.familyvlog.ai

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.firebase.ai.type.Schema
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdkModelClientTest {
    @Test
    fun `two request shapes use their own real ADK agents runners and isolated sessions`() = runBlocking {
        val modelFactory = RecordingAdkModelFactory()
        val invocations = mutableListOf<AdkOneShotInvocation>()
        val client = AdkModelClient(
            modelFactory = modelFactory,
            invocationFactory = AdkOneShotInvocationFactory { role, request, model ->
                createAdkOneShotInvocation(role, request, model).also(invocations::add)
            },
        )
        val videoSchema = Schema.string()
        val storySchema = Schema.obj(mapOf("value" to Schema.string()))
        val bytes = byteArrayOf(0, 2, 4, 6)

        val videoResult = client.generate(
            ModelRequest(
                systemPrompt = "video system",
                schema = videoSchema,
                parts = listOf(
                    ModelPart.InlineVideo(bytes, "video/mp4"),
                    ModelPart.Text("video task"),
                ),
            ),
        )
        val storyResult = client.generate(
            ModelRequest(
                systemPrompt = "story system",
                schema = storySchema,
                parts = listOf(ModelPart.Text("story task")),
            ),
        )

        assertEquals(AiRawResult("response-1", "version-1"), videoResult)
        assertEquals(AiRawResult("response-2", "version-2"), storyResult)
        assertEquals(
            listOf(AdkAgentRole.VIDEO_UNDERSTANDING, AdkAgentRole.STORY_PLANNING),
            modelFactory.roles,
        )
        assertSame(videoSchema, modelFactory.schemas[0])
        assertSame(storySchema, modelFactory.schemas[1])
        assertEquals(2, invocations.size)
        assertNotSame(invocations[0].agent, invocations[1].agent)
        assertNotSame(invocations[0].runner, invocations[1].runner)
        assertNotSame(
            invocations[0].runner.sessionService,
            invocations[1].runner.sessionService,
        )

        assertInvocationContract(
            invocation = invocations[0],
            expectedName = "video_understanding_agent",
            expectedSystemPrompt = "video system",
        )
        assertInvocationContract(
            invocation = invocations[1],
            expectedName = "story_planning_agent",
            expectedSystemPrompt = "story system",
        )

        val videoRequest = modelFactory.models[0].requests.single()
        val storyRequest = modelFactory.models[1].requests.single()
        assertEquals(listOf(false), modelFactory.models[0].streamModes)
        assertEquals(listOf(false), modelFactory.models[1].streamModes)
        assertEquals("video system", videoRequest.config.systemInstruction.singleText())
        assertEquals("story system", storyRequest.config.systemInstruction.singleText())
        assertNull(videoRequest.config.responseSchema)
        assertNull(videoRequest.config.responseMimeType)
        assertNull(videoRequest.config.tools)
        assertEquals(1, videoRequest.contents.size)
        assertEquals(1, storyRequest.contents.size)
        assertEquals(Role.USER, videoRequest.contents.single().role)
        val videoParts = videoRequest.contents.single().parts
        assertEquals(2, videoParts.size)
        assertArrayEquals(bytes, videoParts[0].inlineData!!.data)
        assertEquals("video/mp4", videoParts[0].inlineData!!.mimeType)
        assertEquals("video task", videoParts[1].text)
        assertEquals("story task", storyRequest.contents.single().parts.single().text)
    }

    @Test
    fun `invalid shapes fail before creating a model or ADK invocation`() = runBlocking {
        val modelFactory = RecordingAdkModelFactory()
        var invocationCount = 0
        val client = AdkModelClient(
            modelFactory = modelFactory,
            invocationFactory = AdkOneShotInvocationFactory { role, request, model ->
                invocationCount += 1
                createAdkOneShotInvocation(role, request, model)
            },
        )
        val video = ModelPart.InlineVideo(byteArrayOf(1), "video/mp4")
        val text = ModelPart.Text("task")
        val invalidShapes = listOf(
            emptyList(),
            listOf(text, video),
            listOf(video),
            listOf(video, video),
            listOf(text, text),
            listOf(video, text, text),
        )

        invalidShapes.forEach { parts ->
            try {
                client.generate(ModelRequest("system", Schema.string(), parts))
                throw AssertionError("expected ${AiFailureCode.INVALID_REQUEST_SHAPE}")
            } catch (failure: AiFailureException) {
                assertEquals(AiFailureCode.INVALID_REQUEST_SHAPE, failure.code)
            }
        }

        assertTrue(modelFactory.models.isEmpty())
        assertEquals(0, invocationCount)
    }

    @Test
    fun `ADK runner propagates model cancellation unchanged`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val client = AdkModelClient(
            modelFactory = AdkModelFactory { _, _ -> ThrowingAdkModel(cancellation) },
            invocationFactory = AdkOneShotInvocationFactory(::createAdkOneShotInvocation),
        )

        try {
            client.generate(
                ModelRequest(
                    systemPrompt = "system",
                    schema = Schema.string(),
                    parts = listOf(ModelPart.Text("task")),
                ),
            )
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun assertInvocationContract(
        invocation: AdkOneShotInvocation,
        expectedName: String,
        expectedSystemPrompt: String,
    ) {
        val agent = invocation.agent
        assertEquals(expectedName, agent.name)
        assertEquals(LlmAgent.IncludeContents.NONE, agent.includeContents)
        assertEquals(expectedSystemPrompt, agent.staticInstruction.singleText())
        assertNull(agent.instruction)
        assertNull(agent.generateContentConfig)
        assertTrue(agent.tools.isEmpty())
        assertTrue(agent.toolsets.isEmpty())
        assertTrue(agent.subAgents.isEmpty())
        assertNull(agent.outputSchema)
        assertNull(agent.outputKey)
        assertSame(agent, invocation.runner.agent)
        assertTrue(invocation.runner.sessionService is InMemorySessionService)
        assertNull(invocation.runner.artifactService)
        assertNull(invocation.runner.memoryService)
        assertTrue(invocation.runner.pluginManager.plugins.isEmpty())
        assertEquals(RunConfig(StreamingMode.NONE, maxLlmCalls = 1), invocation.runConfig)
        assertEquals(Role.USER, invocation.newMessage.role)
        assertFalse(invocation.newMessage.parts.isEmpty())
    }

    private fun Content?.singleText(): String? = this?.parts?.singleOrNull()?.text

    private class RecordingAdkModelFactory : AdkModelFactory {
        val roles = mutableListOf<AdkAgentRole>()
        val schemas = mutableListOf<Schema>()
        val models = mutableListOf<RecordingAdkModel>()

        override fun create(role: AdkAgentRole, responseSchema: Schema): Model {
            roles += role
            schemas += responseSchema
            return RecordingAdkModel(models.size + 1).also(models::add)
        }
    }

    private class RecordingAdkModel(private val index: Int) : Model {
        override val name: String = "gemini-3.6-flash"
        val requests = mutableListOf<LlmRequest>()
        val streamModes = mutableListOf<Boolean>()

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
            requests += request
            streamModes += stream
            return flowOf(
                LlmResponse(
                    content = Content(
                        role = Role.MODEL,
                        parts = listOf(Part(text = "response-$index")),
                    ),
                    finishReason = FinishReason.STOP,
                    modelVersion = "version-$index",
                ),
            )
        }
    }

    private class ThrowingAdkModel(private val failure: Throwable) : Model {
        override val name: String = "gemini-3.6-flash"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
            flow { throw failure }
    }
}
