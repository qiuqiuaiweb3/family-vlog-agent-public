package com.chill.familyvlog.ai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FinishReason as AdkFinishReason
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Role
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content as FirebaseContent
import com.google.firebase.ai.type.FinishReason as FirebaseFinishReason
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal data class FirebaseModelSpec(
    val location: String,
    val modelName: String,
    val systemPrompt: String,
    val responseMimeType: String,
    val responseSchema: Schema,
)

internal data class FirebaseResponseSnapshot(
    val candidateCount: Int,
    val finishReason: FirebaseFinishReason?,
    val text: String?,
    val modelVersion: String?,
)

internal fun interface FirebaseOneShotCall {
    suspend fun generate(prompt: FirebaseContent): FirebaseResponseSnapshot
}

internal fun interface FirebaseOneShotCallFactory {
    fun create(spec: FirebaseModelSpec): FirebaseOneShotCall
}

internal object FirebaseAdkModelFactory : AdkModelFactory {
    override fun create(role: AdkAgentRole, responseSchema: Schema): Model =
        FirebaseAdkModel(responseSchema, FirebaseSdkOneShotCallFactory)
}

internal class FirebaseAdkModel(
    private val responseSchema: Schema,
    private val callFactory: FirebaseOneShotCallFactory,
) : Model {
    override val name: String = MODEL_NAME

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        val spec = FirebaseModelSpec(
            location = LOCATION,
            modelName = MODEL_NAME,
            systemPrompt = request.config.systemInstruction
                ?.parts
                ?.joinToString(separator = "") { it.text.orEmpty() }
                .orEmpty(),
            responseMimeType = RESPONSE_MIME_TYPE,
            responseSchema = responseSchema,
        )
        val prompt = content {
            request.contents.forEach { currentContent ->
                currentContent.parts.forEach { part ->
                    val inlineData = part.inlineData
                    val textValue = part.text
                    when {
                        inlineData != null -> inlineData(
                            inlineData.data!!,
                            inlineData.mimeType!!,
                        )
                        textValue != null -> text(textValue)
                    }
                }
            }
        }
        val response = try {
            callFactory.create(spec).generate(prompt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: FirebaseAIException) {
            throw AiFailureException(AiFailureCode.SERVICE_ERROR)
        }

        if (response.candidateCount == 0) {
            throw AiFailureException(AiFailureCode.NO_CANDIDATE)
        }
        if (response.finishReason != FirebaseFinishReason.STOP) {
            throw AiFailureException(AiFailureCode.ABNORMAL_FINISH)
        }
        val text = response.text
        if (text.isNullOrBlank()) {
            throw AiFailureException(AiFailureCode.EMPTY_TEXT)
        }
        emit(
            LlmResponse(
                content = AdkContent(
                    role = Role.MODEL,
                    parts = listOf(AdkPart(text = text)),
                ),
                finishReason = AdkFinishReason.STOP,
                modelVersion = response.modelVersion,
            ),
        )
    }

    private companion object {
        const val LOCATION = "global"
        const val MODEL_NAME = "gemini-3.6-flash"
        const val RESPONSE_MIME_TYPE = "application/json"
    }
}

private object FirebaseSdkOneShotCallFactory : FirebaseOneShotCallFactory {
    override fun create(spec: FirebaseModelSpec): FirebaseOneShotCall {
        val model = Firebase.ai(
            backend = GenerativeBackend.agentPlatform(location = spec.location),
        ).generativeModel(
            modelName = spec.modelName,
            generationConfig = generationConfig {
                responseMimeType = spec.responseMimeType
                responseSchema = spec.responseSchema
            },
            systemInstruction = content { text(spec.systemPrompt) },
        )
        return FirebaseOneShotCall { prompt ->
            val response = model.generateContent(prompt)
            FirebaseResponseSnapshot(
                candidateCount = response.candidates.size,
                finishReason = response.candidates.firstOrNull()?.finishReason,
                text = response.text,
                modelVersion = response.modelVersion,
            )
        }
    }
}
