package com.chill.familyvlog.subtitle

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.chill.familyvlog.render.RenderClip
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@UnstableApi
class AndroidAudioIntervalDecoder internal constructor(
    context: Context,
    private val onCompressedSampleQueued: ((ExtractorSample) -> Unit)?,
) : AudioIntervalDecoder {
    constructor(context: Context) : this(context, onCompressedSampleQueued = null)

    private val applicationContext = context.applicationContext

    override suspend fun decode(clip: RenderClip, consume: suspend (FloatArray) -> Unit) {
        require(clip.hasAudio && clip.startUs >= 0 && clip.endUs > clip.startUs)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var primaryFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            extractor.setDataSource(applicationContext, clip.uri, null)
            val audioTrack = findSingleAudioTrack(extractor)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?.takeIf { it.startsWith("audio/") }
                ?: throw IllegalArgumentException("audio_track_missing_mime")
            extractor.selectTrack(audioTrack)
            extractor.seekTo(clip.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            currentCoroutineContext().ensureActive()
            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true
            currentCoroutineContext().ensureActive()
            decodeCodec(codec, extractor, clip, consume)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val stopFailure = collectCleanupFailure(
                if (codecStarted) ({ codec?.stop() }) else null,
            )
            val releaseFailure = collectCleanupFailure(
                { codec?.release() },
                { extractor.release() },
            )
            if (releaseFailure != null && stopFailure != null) {
                releaseFailure.addSuppressed(stopFailure)
            }
            if (releaseFailure != null) {
                cleanupFailureToThrow(primaryFailure, releaseFailure)?.let { throw it }
            } else if (stopFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(stopFailure)
                } else {
                    throw stopFailure
                }
            }
        }
    }

    private suspend fun decodeCodec(
        codec: MediaCodec,
        extractor: MediaExtractor,
        clip: RenderClip,
        consume: suspend (FloatArray) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var normalizer: PcmNormalizer? = null
        var primaryFailure: Throwable? = null
        try {
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    inputEnded = queueInput(codec, extractor, clip.endUs)
                }
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(normalizer == null)
                        normalizer = PcmNormalizer(
                            codec.outputFormat,
                            clip.endUs - clip.startUs,
                            consume,
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("decoder_output_buffer_missing")
                        if (
                            bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val pcm = output.duplicate().order(ByteOrder.nativeOrder()).apply {
                                position(bufferInfo.offset)
                                limit(bufferInfo.offset + bufferInfo.size)
                            }.slice().order(ByteOrder.nativeOrder())
                            (normalizer ?: throw IllegalStateException("decoder_format_missing"))
                                .queue(pcm, bufferInfo.presentationTimeUs, clip.startUs, clip.endUs)
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            (normalizer ?: throw IllegalStateException("decoder_produced_no_format"))
                .finish()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = collectCleanupFailure({ normalizer?.close() })
            cleanupFailureToThrow(primaryFailure, cleanupFailure)?.let { throw it }
        }
    }

    private fun queueInput(codec: MediaCodec, extractor: MediaExtractor, endUs: Long): Boolean {
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) return false
        val input = codec.getInputBuffer(inputIndex)
            ?: throw IllegalStateException("decoder_input_buffer_missing")
        val sample = readExtractorSample(
            input = input,
            readSampleData = extractor::readSampleData,
            sampleTimeUs = { extractor.sampleTime },
        )
        if (sample == null || sample.presentationTimeUs >= endUs) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                sample?.presentationTimeUs?.coerceAtLeast(0L) ?: endUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            return true
        }
        codec.queueInputBuffer(
            inputIndex,
            0,
            sample.size,
            sample.presentationTimeUs,
            0,
        )
        onCompressedSampleQueued?.invoke(sample)
        extractor.advance()
        return false
    }
}

internal data class ExtractorSample(
    val size: Int,
    val presentationTimeUs: Long,
)

internal inline fun readExtractorSample(
    input: ByteBuffer,
    readSampleData: (ByteBuffer, Int) -> Int,
    sampleTimeUs: () -> Long,
): ExtractorSample? {
    input.clear()
    val size = readSampleData(input, 0)
    if (size < 0) return null
    return ExtractorSample(size, sampleTimeUs())
}

@UnstableApi
private class PcmNormalizer(
    outputFormat: MediaFormat,
    private val clipDurationUs: Long,
    private val consume: suspend (FloatArray) -> Unit,
) : AutoCloseable {
    private val sourceSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private val sourceChannelCount = requireSupportedTranscriptionChannelCount(
        outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
    )
    private val sourceEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }
    private val sourceFrameSize = Util.getPcmFrameSize(sourceEncoding, sourceChannelCount)
    private val pipeline: AudioProcessingPipeline
    private val bypass: Boolean
    private val targetOutputFrames = ceilFrames(clipDurationUs, TRANSCRIPTION_SAMPLE_RATE_HZ)
    private var queuedSourceFrames = 0L
    private var emittedOutputFrames = 0L

    init {
        require(
            clipDurationUs > 0 &&
                targetOutputFrames > 0 &&
                sourceSampleRate > 0 &&
                sourceFrameSize > 0,
        )
        val channelMixer = ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(
                ChannelMixingMatrix.createForConstantPower(sourceChannelCount, 1),
            )
        }
        val resampler = SonicAudioProcessor().apply {
            setOutputSampleRateHz(TRANSCRIPTION_SAMPLE_RATE_HZ)
        }
        pipeline = AudioProcessingPipeline(
            ImmutableList.of(ToInt16PcmAudioProcessor(), channelMixer, resampler),
        )
        val result = pipeline.configure(
            AudioProcessor.AudioFormat(sourceSampleRate, sourceChannelCount, sourceEncoding),
        )
        require(
            result.sampleRate == TRANSCRIPTION_SAMPLE_RATE_HZ &&
                result.channelCount == 1 &&
                result.encoding == C.ENCODING_PCM_16BIT,
        )
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        bypass = !pipeline.isOperational
        if (bypass) {
            require(
                sourceSampleRate == TRANSCRIPTION_SAMPLE_RATE_HZ &&
                    sourceChannelCount == 1 &&
                    sourceEncoding == C.ENCODING_PCM_16BIT,
            )
        }
    }

    suspend fun queue(
        pcm: ByteBuffer,
        presentationTimeUs: Long,
        clipStartUs: Long,
        clipEndUs: Long,
    ) {
        require(pcm.remaining() % sourceFrameSize == 0)
        val bufferFrameCount = pcm.remaining() / sourceFrameSize
        val window = selectPcmFrameWindow(
            bufferFrameCount = bufferFrameCount,
            presentationTimeUs = presentationTimeUs,
            clipStartUs = clipStartUs,
            clipEndUs = clipEndUs,
            sampleRate = sourceSampleRate,
        ) ?: return
        var relativeStartFrame = window.relativeStartFrame
        var selectedFirstFrame = window.firstFrame
        if (relativeStartFrame < queuedSourceFrames) {
            val overlapFrames = minOf(
                queuedSourceFrames - relativeStartFrame,
                window.endFrameExclusive - window.firstFrame,
            )
            selectedFirstFrame += overlapFrames
            relativeStartFrame += overlapFrames
        }
        if (relativeStartFrame > queuedSourceFrames) {
            queueSilence(relativeStartFrame - queuedSourceFrames)
        }
        if (selectedFirstFrame >= window.endFrameExclusive) return

        val selected = pcm.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(Math.toIntExact(selectedFirstFrame * sourceFrameSize))
            limit(Math.toIntExact(window.endFrameExclusive * sourceFrameSize))
        }.slice().order(ByteOrder.nativeOrder())
        queueNormalized(selected)
        queuedSourceFrames = addExact(
            queuedSourceFrames,
            window.endFrameExclusive - selectedFirstFrame,
        )
    }

    suspend fun finish() {
        val expectedSourceFrames = ceilFrames(clipDurationUs, sourceSampleRate)
        if (queuedSourceFrames < expectedSourceFrames) {
            queueSilence(expectedSourceFrames - queuedSourceFrames)
        }
        if (!bypass) {
            pipeline.queueEndOfStream()
            var stalledCount = 0
            while (!pipeline.isEnded) {
                val produced = drainPipeline()
                stalledCount = if (produced) 0 else stalledCount + 1
                check(stalledCount < MAX_PIPELINE_STALL_COUNT)
            }
        }
        padOutputToTarget()
    }

    override fun close() {
        pipeline.reset()
    }

    private suspend fun queueSilence(frameCount: Long) {
        var remaining = frameCount
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val frames = minOf(remaining, SILENCE_CHUNK_FRAMES.toLong()).toInt()
            val silence = ByteBuffer.allocateDirect(frames * sourceFrameSize)
                .order(ByteOrder.nativeOrder())
            queueNormalized(silence)
            queuedSourceFrames = addExact(queuedSourceFrames, frames.toLong())
            remaining -= frames
        }
    }

    private suspend fun queueNormalized(input: ByteBuffer) {
        if (bypass) {
            emitPcm16(input)
            return
        }
        while (input.hasRemaining()) {
            currentCoroutineContext().ensureActive()
            val position = input.position()
            pipeline.queueInput(input)
            val produced = drainPipeline()
            check(input.position() != position || produced)
        }
        drainPipeline()
    }

    private suspend fun drainPipeline(): Boolean {
        var produced = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val output = pipeline.output
            if (!output.hasRemaining()) return produced
            produced = true
            emitPcm16(output)
        }
    }

    private suspend fun emitPcm16(buffer: ByteBuffer) {
        require(buffer.remaining() % Short.SIZE_BYTES == 0)
        val availableFrames = buffer.remaining() / Short.SIZE_BYTES
        val remainingTargetFrames = targetOutputFrames - emittedOutputFrames
        val emittedFrames = minOf(availableFrames.toLong(), maxOf(0L, remainingTargetFrames)).toInt()
        val samples = FloatArray(emittedFrames)
        val pcm = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (index in samples.indices) {
            samples[index] = pcm.get().toFloat() / 32768f
        }
        buffer.position(buffer.limit())
        if (samples.isNotEmpty()) {
            consume(samples)
            emittedOutputFrames = addExact(emittedOutputFrames, samples.size.toLong())
        }
    }

    private suspend fun padOutputToTarget() {
        while (emittedOutputFrames < targetOutputFrames) {
            currentCoroutineContext().ensureActive()
            val frameCount = minOf(
                targetOutputFrames - emittedOutputFrames,
                SILENCE_CHUNK_FRAMES.toLong(),
            ).toInt()
            consume(FloatArray(frameCount))
            emittedOutputFrames = addExact(emittedOutputFrames, frameCount.toLong())
        }
    }
}

internal fun requireSupportedTranscriptionChannelCount(channelCount: Int): Int {
    require(channelCount in 1..6) { "unsupported_transcription_channel_count" }
    return channelCount
}

private fun collectCleanupFailure(vararg cleanups: (() -> Unit)?): Throwable? {
    var firstFailure: Throwable? = null
    cleanups.forEach { cleanup ->
        if (cleanup == null) return@forEach
        try {
            cleanup()
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure.addSuppressed(failure)
            }
        }
    }
    return firstFailure
}

internal fun cleanupFailureToThrow(
    primaryFailure: Throwable?,
    cleanupFailure: Throwable?,
): Throwable? {
    if (cleanupFailure == null) return null
    return if (primaryFailure == null) {
        cleanupFailure
    } else if (primaryFailure is CancellationException) {
        cleanupFailure.apply { addSuppressed(primaryFailure) }
    } else {
        primaryFailure.addSuppressed(cleanupFailure)
        null
    }
}

private fun findSingleAudioTrack(extractor: MediaExtractor): Int {
    var found = -1
    repeat(extractor.trackCount) { trackIndex ->
        val mimeType = extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)
        if (mimeType?.startsWith("audio/") == true) {
            check(found == -1)
            found = trackIndex
        }
    }
    check(found >= 0)
    return found
}

internal fun ceilFrames(durationUs: Long, sampleRate: Int): Long {
    require(sampleRate > 0)
    val numerator = Math.multiplyExact(durationUs, sampleRate.toLong())
    val floor = Math.floorDiv(numerator, MICROSECONDS_PER_SECOND)
    return if (numerator % MICROSECONDS_PER_SECOND == 0L) floor else floor + 1L
}

internal data class PcmFrameWindow(
    val firstFrame: Long,
    val endFrameExclusive: Long,
    val relativeStartFrame: Long,
)

internal fun selectPcmFrameWindow(
    bufferFrameCount: Int,
    presentationTimeUs: Long,
    clipStartUs: Long,
    clipEndUs: Long,
    sampleRate: Int,
): PcmFrameWindow? {
    require(bufferFrameCount >= 0 && clipEndUs > clipStartUs)
    val firstFrame = ceilFrames(Math.subtractExact(clipStartUs, presentationTimeUs), sampleRate)
        .coerceIn(0L, bufferFrameCount.toLong())
    val endFrame = ceilFrames(Math.subtractExact(clipEndUs, presentationTimeUs), sampleRate)
        .coerceIn(0L, bufferFrameCount.toLong())
    if (endFrame <= firstFrame) return null
    return PcmFrameWindow(
        firstFrame = firstFrame,
        endFrameExclusive = endFrame,
        relativeStartFrame = addExact(
            ceilFrames(Math.subtractExact(presentationTimeUs, clipStartUs), sampleRate),
            firstFrame,
        ),
    )
}

private fun addExact(first: Long, second: Long): Long = Math.addExact(first, second)

private const val CODEC_TIMEOUT_US = 10_000L
private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val SILENCE_CHUNK_FRAMES = 4_096
private const val MAX_PIPELINE_STALL_COUNT = 1_000
