package com.chill.familyvlog.render

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class Media3RenderConfigurationTest {
    @Test
    fun `one composition preserves plan order and uses default transcode with OpenGL tone mapping`() {
        val firstUri = mock(Uri::class.java)
        val secondUri = mock(Uri::class.java)
        val composition = buildComposition(
            RenderSpec(
                clips = listOf(
                    RenderClip(firstUri, startUs = 2_000_000L, endUs = 3_000_000L, hasAudio = false),
                    RenderClip(secondUri, startUs = 4_000_000L, endUs = 6_000_000L, hasAudio = false),
                ),
                expectsAudio = false,
                canvasAspectRatio = 16f / 9f,
            ),
        )

        assertEquals(1, composition.sequences.size)
        assertFalse(composition.transmuxVideo)
        assertFalse(composition.transmuxAudio)
        assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, composition.hdrMode)
        val items = composition.sequences.single().editedMediaItems
        assertSame(firstUri, items[0].mediaItem.localConfiguration!!.uri)
        assertSame(secondUri, items[1].mediaItem.localConfiguration!!.uri)
        assertEquals(2_000_000L, items[0].mediaItem.clippingConfiguration.startPositionUs)
        assertEquals(3_000_000L, items[0].mediaItem.clippingConfiguration.endPositionUs)
        assertEquals(4_000_000L, items[1].mediaItem.clippingConfiguration.startPositionUs)
        assertEquals(6_000_000L, items[1].mediaItem.clippingConfiguration.endPositionUs)
        assertTrue(items[0].removeAudio)
        assertTrue(items[1].removeAudio)
        assertEquals(1, composition.effects.videoEffects.size)
        assertTrue(composition.effects.videoEffects.single() is Presentation)
    }

    @Test
    fun `invalid spec is rejected before Media3 starts`() {
        val uri = mock(Uri::class.java)
        listOf(
            RenderSpec(emptyList(), expectsAudio = false, canvasAspectRatio = 1f),
            RenderSpec(listOf(RenderClip(uri, 0L, 1L, true)), expectsAudio = false, canvasAspectRatio = 1f),
            RenderSpec(listOf(RenderClip(uri, -1L, 1L, false)), expectsAudio = false, canvasAspectRatio = 1f),
            RenderSpec(listOf(RenderClip(uri, 1L, 1L, false)), expectsAudio = false, canvasAspectRatio = 1f),
            RenderSpec(listOf(RenderClip(uri, 0L, 1L, false)), expectsAudio = false, canvasAspectRatio = 0f),
            RenderSpec(listOf(RenderClip(uri, 0L, 1L, false)), expectsAudio = false, canvasAspectRatio = Float.NaN),
        ).forEach { spec ->
            try {
                buildComposition(spec)
                fail("Expected invalid render spec")
            } catch (failure: PipelineException) {
                assertEquals(RunPhase.RENDERING, failure.phase)
                assertEquals(RunFailureCode.RENDER_FAILED, failure.code)
            }
        }
    }
}
