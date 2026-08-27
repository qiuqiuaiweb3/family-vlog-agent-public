package com.chill.familyvlog.media

import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.chill.familyvlog.pipeline.selectAnalysisProxyHdrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class AndroidMediaTrackSelectionTest {
    @Test
    fun sameIdDolbyVisionAndHevcFormats_selectBaseVideoAndAudio() {
        val dolby = format("video/dolby-vision", trackId = 0)
        val base = format("video/hevc", trackId = 0)
        val audio = format("audio/mp4a-latm")

        val selection = selectAndroidMediaTracks(listOf(dolby, base, audio))

        assertEquals(1, selection.logicalVideoTrackCount)
        assertEquals(1, selection.singleVideoTrack!!.index)
        assertEquals("video/hevc", selection.singleVideoTrack!!.mimeType)
        assertEquals(listOf(1, 2), selection.selectedTracks.map(MediaTrackDescriptor::index))
    }

    @Test
    fun missingOrWrongTypedTrackId_isMappedSafelyAndDoesNotMerge() {
        val missing = format("video/dolby-vision")
        val wrongType = format("video/hevc").apply {
            setString(MediaFormat.KEY_TRACK_ID, "7")
        }
        val noMime = MediaFormat().apply {
            setInteger(MediaFormat.KEY_TRACK_ID, 7)
        }

        val selection = selectAndroidMediaTracks(listOf(missing, wrongType, noMime))

        assertEquals(2, selection.logicalVideoTrackCount)
        assertNull(selection.singleVideoTrack)
        assertEquals(0, selection.primaryVideoTrack!!.index)
    }

    @Test
    fun DolbyVisionHlgAndPq_selectOpenGlToneMapping() {
        listOf(
            listOf(format("video/dolby-vision")),
            listOf(format("video/hevc", colorTransfer = MediaFormat.COLOR_TRANSFER_HLG)),
            listOf(format("video/avc", colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084)),
        ).forEach { formats ->
            assertEquals(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                selectAnalysisProxyHdrMode(formats),
            )
        }
    }

    @Test
    fun SdrAndUnknownTransfer_keepTheExistingHdrMode() {
        listOf(
            listOf(format("video/avc", colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO)),
            listOf(format("video/hevc")),
            listOf(format("video/hevc").apply {
                setString(MediaFormat.KEY_COLOR_TRANSFER, "7")
            }),
        ).forEach { formats ->
            assertEquals(Composition.HDR_MODE_KEEP_HDR, selectAnalysisProxyHdrMode(formats))
        }
    }

    private fun format(
        mimeType: String,
        trackId: Int? = null,
        colorTransfer: Int? = null,
    ): MediaFormat = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, mimeType)
        if (trackId != null) setInteger(MediaFormat.KEY_TRACK_ID, trackId)
        if (colorTransfer != null) setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer)
    }
}
