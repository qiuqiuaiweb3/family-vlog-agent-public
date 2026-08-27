package com.chill.familyvlog.subtitle

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

internal const val TRANSCRIPTION_SAMPLE_RATE_HZ = 16_000
internal const val LOCAL_ASR_MODEL_DIRECTORY =
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

class SherpaSpeechSessionFactory(
    private val assetManager: AssetManager,
) : SpeechSessionFactory {
    override fun create(): SpeechSession = SherpaSpeechSession(assetManager)
}

private class SherpaSpeechSession(
    assetManager: AssetManager,
) : SpeechSession {
    private val vad: Vad
    private val recognizer: OfflineRecognizer
    private var pendingSamples = FloatArray(0)
    private var closed = false

    init {
        val createdVad = Vad(
            assetManager = assetManager,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 5f,
                ),
                sampleRate = TRANSCRIPTION_SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val createdRecognizer = try {
            OfflineRecognizer(
                assetManager = assetManager,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = TRANSCRIPTION_SAMPLE_RATE_HZ,
                        featureDim = 80,
                    ),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "$LOCAL_ASR_MODEL_DIRECTORY/model.int8.onnx",
                            language = "auto",
                            useInverseTextNormalization = false,
                        ),
                        tokens = "$LOCAL_ASR_MODEL_DIRECTORY/tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                        debug = false,
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        } catch (failure: Throwable) {
            try {
                createdVad.release()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        vad = createdVad
        recognizer = createdRecognizer
    }

    override fun reset() {
        check(!closed)
        pendingSamples = FloatArray(0)
        vad.reset()
    }

    override fun accept(samples: FloatArray): List<SpeechSegmentSamples> {
        check(!closed)
        val combined = if (pendingSamples.isEmpty()) {
            samples
        } else {
            FloatArray(pendingSamples.size + samples.size).also { output ->
                pendingSamples.copyInto(output)
                samples.copyInto(output, pendingSamples.size)
            }
        }
        var offset = 0
        while (combined.size - offset >= VAD_WINDOW_SIZE) {
            vad.acceptWaveform(combined.copyOfRange(offset, offset + VAD_WINDOW_SIZE))
            offset += VAD_WINDOW_SIZE
        }
        pendingSamples = combined.copyOfRange(offset, combined.size)
        return drainSegments()
    }

    override fun flush(): List<SpeechSegmentSamples> {
        check(!closed)
        if (pendingSamples.isNotEmpty()) {
            vad.acceptWaveform(
                FloatArray(VAD_WINDOW_SIZE).also(pendingSamples::copyInto),
            )
            pendingSamples = FloatArray(0)
        }
        vad.flush()
        return drainSegments()
    }

    override fun recognize(samples: FloatArray): SpeechRecognition {
        check(!closed)
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, TRANSCRIPTION_SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            SpeechRecognition(
                language = result.lang,
                tokens = result.tokens.toList(),
                timestampsSeconds = result.timestamps.toList(),
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingSamples = FloatArray(0)
        var releaseFailure: Throwable? = null
        try {
            vad.release()
        } catch (failure: Throwable) {
            releaseFailure = failure
        }
        try {
            recognizer.release()
        } catch (failure: Throwable) {
            if (releaseFailure == null) {
                releaseFailure = failure
            } else {
                releaseFailure.addSuppressed(failure)
            }
        }
        releaseFailure?.let { throw it }
    }

    private fun drainSegments(): List<SpeechSegmentSamples> = buildList {
        while (!vad.empty()) {
            val segment = vad.front()
            add(SpeechSegmentSamples(segment.start, segment.samples))
            vad.pop()
        }
    }
}

private const val VAD_WINDOW_SIZE = 512
