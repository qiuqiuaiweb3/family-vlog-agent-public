package com.chill.familyvlog.ai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FinishReason as AdkFinishReason
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Role
import com.google.firebase.ai.type.Content as FirebaseContent
import com.google.firebase.ai.type.FirebaseAutoFunctionException
import com.google.firebase.ai.type.FinishReason as FirebaseFinishReason
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirebaseAdkModelTest {
    @Test
    fun `bridge preserves exact Firebase config ordered bytes response text and model version`() = runBlocking {
        val factory = RecordingFactory {
            FirebaseResponseSnapshot(1, FirebaseFinishReason.STOP, "{\"ok\":true}", "model-version")
        }
        val firstSchema = Schema.string()
        val secondSchema = Schema.obj(mapOf("value" to Schema.string()))
        val firstBytes = byteArrayOf(0, 2, 4, 6)
        val firstModel = FirebaseAdkModel(firstSchema, factory)
        val secondModel = FirebaseAdkModel(secondSchema, factory)

        val first = firstModel.generateContent(
            adkRequest(
                systemPrompt = "first system",
                parts = listOf(
                    AdkPart(inlineData = Blob(mimeType = "video/mp4", data = firstBytes)),
                    AdkPart(text = "first task"),
                ),
            ),
        ).single()
        val second = secondModel.generateContent(
            adkRequest(systemPrompt = "second system", parts = listOf(AdkPart(text = "second task"))),
        ).single()

        assertEquals("gemini-3.6-flash", firstModel.name)
        assertEquals("{\"ok\":true}", first.content!!.parts.single().text)
        assertEquals("model-version", first.modelVersion)
        assertEquals(AdkFinishReason.STOP, first.finishReason)
        assertEquals("{\"ok\":true}", second.content!!.parts.single().text)
        assertEquals("model-version", second.modelVersion)
        assertEquals(2, factory.specs.size)
        assertEquals(2, factory.calls.size)
        assertNotSame(factory.calls[0], factory.calls[1])
        assertEquals(listOf(1, 1), factory.calls.map { it.invocationCount })

        val firstSpec = factory.specs[0]
        assertEquals("global", firstSpec.location)
        assertEquals("gemini-3.6-flash", firstSpec.modelName)
        assertEquals("first system", firstSpec.systemPrompt)
        assertEquals("application/json", firstSpec.responseMimeType)
        assertSame(firstSchema, firstSpec.responseSchema)
        val secondSpec = factory.specs[1]
        assertEquals("second system", secondSpec.systemPrompt)
        assertSame(secondSchema, secondSpec.responseSchema)

        val firstParts = factory.calls[0].prompts.single().parts
        assertEquals(2, firstParts.size)
        assertTrue(firstParts[0] is InlineDataPart)
        val inline = firstParts[0] as InlineDataPart
        assertArrayEquals(firstBytes, inline.inlineData)
        assertEquals("video/mp4", inline.mimeType)
        assertTrue(firstParts[1] is TextPart)
        assertEquals("first task", (firstParts[1] as TextPart).text)

        val secondParts = factory.calls[1].prompts.single().parts
        assertEquals(1, secondParts.size)
        assertTrue(secondParts.single() is TextPart)
        assertEquals("second task", (secondParts.single() as TextPart).text)
    }

    @Test
    fun `response truth table rejects absent abnormal and empty responses`() = runBlocking {
        val failures = listOf(
            FirebaseResponseSnapshot(0, null, "ignored", "v") to AiFailureCode.NO_CANDIDATE,
            FirebaseResponseSnapshot(1, null, null, "v") to AiFailureCode.ABNORMAL_FINISH,
            FirebaseResponseSnapshot(1, FirebaseFinishReason.MAX_TOKENS, " \n\t", "v") to
                AiFailureCode.ABNORMAL_FINISH,
            FirebaseResponseSnapshot(1, FirebaseFinishReason.STOP, null, "v") to AiFailureCode.EMPTY_TEXT,
            FirebaseResponseSnapshot(1, FirebaseFinishReason.STOP, "", "v") to AiFailureCode.EMPTY_TEXT,
            FirebaseResponseSnapshot(1, FirebaseFinishReason.STOP, " \n\t", "v") to AiFailureCode.EMPTY_TEXT,
        )

        failures.forEach { (snapshot, code) ->
            val model = FirebaseAdkModel(Schema.string(), RecordingFactory { snapshot })
            expectFailure(code) { model.generateContent(adkRequest()).single() }
        }
    }

    @Test
    fun `successful response preserves text and nullable model version exactly`() = runBlocking {
        val text = "  {\"clips\":[]}\n"
        val model = FirebaseAdkModel(
            Schema.string(),
            RecordingFactory {
                FirebaseResponseSnapshot(2, FirebaseFinishReason.STOP, text, null)
            },
        )

        val response = model.generateContent(adkRequest()).single()

        assertEquals(text, response.content!!.parts.single().text)
        assertEquals(null, response.modelVersion)
    }

    @Test
    fun `official SDK exception maps to one fixed private service failure`() = runBlocking {
        val model = FirebaseAdkModel(Schema.string(), RecordingFactory {
            throw FirebaseAutoFunctionException("private SDK message with content URI")
        })

        val failure = expectFailure(AiFailureCode.SERVICE_ERROR) {
            model.generateContent(adkRequest()).single()
        }

        assertEquals("SERVICE_ERROR", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `cancellation is propagated unchanged`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val model = FirebaseAdkModel(Schema.string(), RecordingFactory { throw cancellation })

        try {
            model.generateContent(adkRequest()).single()
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun adkRequest(
        systemPrompt: String = "system",
        parts: List<AdkPart> = listOf(AdkPart(text = "task")),
    ) = LlmRequest(
        contents = listOf(AdkContent(role = Role.USER, parts = parts)),
        config = GenerateContentConfig(
            systemInstruction = AdkContent.fromText(Role.SYSTEM, systemPrompt),
        ),
    )

    private suspend fun expectFailure(
        expected: AiFailureCode,
        block: suspend () -> Unit,
    ): AiFailureException {
        try {
            block()
            throw AssertionError("expected $expected")
        } catch (failure: AiFailureException) {
            assertEquals(expected, failure.code)
            assertEquals(expected.name, failure.message)
            return failure
        }
    }

    private class RecordingFactory(
        private val behavior: suspend (FirebaseContent) -> FirebaseResponseSnapshot,
    ) : FirebaseOneShotCallFactory {
        val specs = mutableListOf<FirebaseModelSpec>()
        val calls = mutableListOf<RecordingCall>()

        override fun create(spec: FirebaseModelSpec): FirebaseOneShotCall {
            specs += spec
            return RecordingCall(behavior).also(calls::add)
        }
    }

    private class RecordingCall(
        private val behavior: suspend (FirebaseContent) -> FirebaseResponseSnapshot,
    ) : FirebaseOneShotCall {
        val prompts = mutableListOf<FirebaseContent>()
        var invocationCount = 0

        override suspend fun generate(prompt: FirebaseContent): FirebaseResponseSnapshot {
            invocationCount += 1
            prompts += prompt
            return behavior(prompt)
        }
    }
}
