package com.chill.familyvlog.pipeline

import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class AnalysisProxyConfigurationTest {
    @Test
    fun `proxy composition uses the selected HDR mode and preserves requested audio semantics`() {
        val uri = mock(Uri::class.java)
        val videoEffect = mock(Effect::class.java)

        listOf(
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            Composition.HDR_MODE_KEEP_HDR,
        ).forEach { hdrMode ->
            listOf(true, false).forEach { hasAudio ->
                val composition = buildAnalysisProxyComposition(
                    uri = uri,
                    startUs = 2_000_000L,
                    endUs = 5_000_000L,
                    hasAudio = hasAudio,
                    hdrMode = hdrMode,
                    videoEffect = videoEffect,
                )

                assertEquals(hdrMode, composition.hdrMode)
                assertFalse(composition.transmuxVideo)
                assertFalse(composition.transmuxAudio)
                val item = composition.sequences.single().editedMediaItems.single()
                assertSame(uri, item.mediaItem.localConfiguration!!.uri)
                assertEquals(2_000_000L, item.mediaItem.clippingConfiguration.startPositionUs)
                assertEquals(5_000_000L, item.mediaItem.clippingConfiguration.endPositionUs)
                assertEquals(!hasAudio, item.removeAudio)
                assertFalse(item.removeVideo)
                assertTrue(item.effects.audioProcessors.isEmpty())
                assertEquals(1, item.effects.videoEffects.size)
                assertSame(videoEffect, item.effects.videoEffects.single())
            }
        }
    }
}
