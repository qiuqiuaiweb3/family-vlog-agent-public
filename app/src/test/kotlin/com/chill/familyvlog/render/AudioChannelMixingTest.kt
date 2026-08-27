package com.chill.familyvlog.render

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class AudioChannelMixingTest {
    @Test
    fun `audio clips give the processor every official one through six channel matrix to stereo`() {
        val processor = mock(AudioProcessor::class.java)
        lateinit var matrices: List<ChannelMixingMatrix>

        val effects = buildAudioEffects(hasAudio = true) { requestedMatrices ->
            matrices = requestedMatrices
            processor
        }

        assertEquals((1..6).toList(), matrices.map { it.inputChannelCount })
        assertTrue(matrices.all { it.outputChannelCount == 2 })
        assertSame(processor, effects.audioProcessors.single())
    }

    @Test
    fun `silent clips do not add an audio processor`() {
        var factoryCalled = false
        val effects = buildAudioEffects(hasAudio = false) {
            factoryCalled = true
            mock(AudioProcessor::class.java)
        }

        assertTrue(effects.audioProcessors.isEmpty())
        assertTrue(!factoryCalled)
    }

    @Test
    fun `unsupported channel count is not assigned an invented layout`() {
        lateinit var matrices: List<ChannelMixingMatrix>
        buildAudioEffects(hasAudio = true) {
            matrices = it
            mock(AudioProcessor::class.java)
        }

        assertTrue(matrices.none { it.inputChannelCount == 7 })
    }
}
