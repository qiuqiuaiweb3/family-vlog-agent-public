package com.chill.familyvlog.subtitle

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.transformer.Composition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@UnstableApi
class Media3SubtitleConfigurationTest {
    @Test
    fun `subtitle composition uses one full source one effect and audio transmux`() {
        val uri = mock(Uri::class.java)
        val effect = mock(GlEffect::class.java)
        val source = SubtitleSource(
            uri = uri,
            durationUs = 2_000_000L,
            videoWidth = 720,
            videoHeight = 1_280,
            hasAudio = true,
        )

        val composition = buildSubtitleComposition(source, effect)

        assertEquals(1, composition.sequences.size)
        val item = composition.sequences.single().editedMediaItems.single()
        assertSame(uri, item.mediaItem.localConfiguration!!.uri)
        assertEquals(0L, item.mediaItem.clippingConfiguration.startPositionUs)
        assertEquals(C.TIME_END_OF_SOURCE, item.mediaItem.clippingConfiguration.endPositionUs)
        assertFalse(item.removeAudio)
        assertFalse(item.removeVideo)
        assertTrue(composition.transmuxAudio)
        assertFalse(composition.transmuxVideo)
        assertEquals(Composition.HDR_MODE_KEEP_HDR, composition.hdrMode)
        assertTrue(composition.effects.audioProcessors.isEmpty())
        assertEquals(listOf(effect), composition.effects.videoEffects)
        assertTrue(item.effects.audioProcessors.isEmpty())
        assertTrue(item.effects.videoEffects.isEmpty())
    }
}
