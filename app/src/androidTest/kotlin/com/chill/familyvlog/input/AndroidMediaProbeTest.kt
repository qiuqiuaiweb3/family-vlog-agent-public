package com.chill.familyvlog.input

import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMediaProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun fixtureProvider_resolvesKnownAssetAndOpensSeekableDescriptor() {
        val uri = FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO)

        assertEquals("video/mp4", context.contentResolver.getType(uri))
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
        assertNotNull(descriptor)
        descriptor?.use {
            assertTrue(it.length > 0)
        }
    }

    @Test
    fun portraitAudioFixture_isReadableDecodedAndAccepted() = runBlocking {
        val result = AndroidMediaProbe(context).inspect(FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO))

        assertTrue(result.readable)
        assertTrue(result.durationUs!! > 0)
        assertNotNull(result.videoTrack)
        assertEquals("video/mp4", result.resolverMimeType)
        assertEquals("video/mp4", result.containerMimeType)
        assertEquals("video/avc", result.videoTrack!!.mimeType)
        assertTrue(result.videoTrack!!.height!! > result.videoTrack!!.width!!)
        assertTrue(result.audioTracks.isNotEmpty())
        assertEquals(1, result.videoTrackCount)
        assertTrue(result.firstSyncFrameDecoded)
        assertEquals(SourceDecision.Accepted, evaluate(result))
    }

    @Test
    fun landscapeSilentFixture_isReadableDecodedAndAccepted() = runBlocking {
        val result = AndroidMediaProbe(context).inspect(FixtureMediaProvider.uriFor(FixtureMediaProvider.LANDSCAPE_SILENT))

        assertTrue(result.readable)
        assertTrue(result.durationUs!! > 0)
        assertNotNull(result.videoTrack)
        assertEquals("video/mp4", result.resolverMimeType)
        assertEquals("video/mp4", result.containerMimeType)
        assertEquals("video/avc", result.videoTrack!!.mimeType)
        assertTrue(result.videoTrack!!.width!! > result.videoTrack!!.height!!)
        assertTrue(result.audioTracks.isEmpty())
        assertEquals(1, result.videoTrackCount)
        assertTrue(result.firstSyncFrameDecoded)
        assertEquals(SourceDecision.Accepted, evaluate(result))
    }

    @Test
    fun renderFixtures_exposeRealRotationAndColorTransferToTheProductionProbe() = runBlocking {
        val rotated = AndroidMediaProbe(context).inspect(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_ROTATED_SILENT),
        )
        val hlg = AndroidMediaProbe(context).inspect(
            FixtureMediaProvider.uriFor(FixtureMediaProvider.RENDER_HEVC_HLG),
        )

        assertTrue(rotated.readable)
        assertEquals(320, rotated.videoTrack!!.width)
        assertEquals(576, rotated.videoTrack!!.height)
        assertEquals(90, Math.floorMod(rotated.videoTrack!!.rotationDegrees!!, 180))
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, rotated.videoTrack!!.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, rotated.videoTrack!!.colorTransfer)
        assertTrue(hlg.readable)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, hlg.videoTrack!!.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, hlg.videoTrack!!.colorTransfer)
    }

    @Test
    fun corruptFirstSyncFixture_staysReadableButFailsActualFrameDecode() = runBlocking {
        val result = AndroidMediaProbe(context).inspect(FixtureMediaProvider.uriFor(FixtureMediaProvider.CORRUPT_FIRST_SYNC))

        assertTrue(result.readable)
        assertTrue(result.durationUs!! > 0)
        assertNotNull(result.videoTrack)
        assertTrue(!result.firstSyncFrameDecoded)
        assertEquals(SourceDecision.Rejected(RejectionReason.VIDEO_DECODE_FAILED), evaluate(result))
    }

    @Test
    fun assignSourceIds_preservesFixtureUriInstancesAndCallbackOrder() {
        val firstCallbackUri = FixtureMediaProvider.uriFor(FixtureMediaProvider.LANDSCAPE_SILENT)
        val secondCallbackUri = FixtureMediaProvider.uriFor(FixtureMediaProvider.PORTRAIT_AUDIO)

        val sources = assignSourceIds(listOf(firstCallbackUri, secondCallbackUri))

        assertEquals(listOf(1, 2), sources.map { it.sourceOrder })
        assertEquals(listOf("video_01", "video_02"), sources.map { it.sourceId })
        assertSame(firstCallbackUri, sources[0].uri)
        assertSame(secondCallbackUri, sources[1].uri)
    }
}
