package com.chill.familyvlog.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicalMediaTrackSelectorTest {
    @Test
    fun `same track id Dolby Vision pairs select the base representation in either order`() {
        listOf(
            Triple(VIDEO_HEVC, 17, false),
            Triple(VIDEO_HEVC, 17, true),
            Triple(VIDEO_AVC, 0, false),
            Triple(VIDEO_AVC, -9, true),
        ).forEach { (baseMimeType, trackId, baseFirst) ->
            val dolby = track(4, VIDEO_DOLBY_VISION, trackId)
            val base = track(8, baseMimeType, trackId)
            val tracks = if (baseFirst) listOf(base, dolby) else listOf(dolby, base)

            val selection = selectLogicalMediaTracks(tracks)

            assertEquals(1, selection.logicalVideoTrackCount)
            assertEquals(base, selection.primaryVideoTrack)
            assertEquals(base, selection.singleVideoTrack)
            assertEquals(listOf(base), selection.selectedTracks)
        }
    }

    @Test
    fun `different or missing ids and non-exact mime pairs remain separate video tracks`() {
        listOf(
            listOf(track(1, VIDEO_DOLBY_VISION, 7), track(2, VIDEO_HEVC, 8)),
            listOf(track(1, VIDEO_DOLBY_VISION, null), track(2, VIDEO_HEVC, 7)),
            listOf(track(1, VIDEO_DOLBY_VISION, 7), track(2, VIDEO_HEVC, null)),
            listOf(track(1, VIDEO_DOLBY_VISION, null), track(2, VIDEO_HEVC, null)),
            listOf(track(1, "video/Dolby-Vision", 7), track(2, VIDEO_HEVC, 7)),
        ).forEach { tracks ->
            val selection = selectLogicalMediaTracks(tracks)

            assertEquals(2, selection.logicalVideoTrackCount)
            assertEquals(tracks.first(), selection.primaryVideoTrack)
            assertNull(selection.singleVideoTrack)
        }
    }

    @Test
    fun `an additional video track prevents a matching pair from being collapsed`() {
        val dolby = track(0, VIDEO_DOLBY_VISION, 12)
        val base = track(1, VIDEO_HEVC, 12)
        val additional = track(2, VIDEO_AVC, 20)

        val selection = selectLogicalMediaTracks(listOf(dolby, base, additional))

        assertEquals(3, selection.logicalVideoTrackCount)
        assertEquals(dolby, selection.primaryVideoTrack)
        assertNull(selection.singleVideoTrack)
    }

    @Test
    fun `ordinary zero and one video track behavior is unchanged`() {
        val audio = track(3, AUDIO_AAC, null)
        val noVideo = selectLogicalMediaTracks(listOf(audio))
        assertEquals(0, noVideo.logicalVideoTrackCount)
        assertNull(noVideo.primaryVideoTrack)
        assertNull(noVideo.singleVideoTrack)
        assertEquals(listOf(audio), noVideo.selectedTracks)

        val video = track(6, VIDEO_HEVC, null)
        val oneVideo = selectLogicalMediaTracks(listOf(audio, video))
        assertEquals(1, oneVideo.logicalVideoTrackCount)
        assertEquals(video, oneVideo.primaryVideoTrack)
        assertEquals(video, oneVideo.singleVideoTrack)
        assertEquals(listOf(audio, video), oneVideo.selectedTracks)
    }

    @Test
    fun `selected tracks retain audio and exclude the Dolby Vision representation`() {
        val firstAudio = track(0, AUDIO_AAC, null)
        val dolby = track(1, VIDEO_DOLBY_VISION, -3)
        val base = track(2, VIDEO_HEVC, -3)
        val metadata = track(3, "application/octet-stream", null)
        val secondAudio = track(4, "audio/opus", null)

        val selection = selectLogicalMediaTracks(
            listOf(firstAudio, dolby, base, metadata, secondAudio),
        )

        assertEquals(1, selection.logicalVideoTrackCount)
        assertEquals(base, selection.singleVideoTrack)
        assertEquals(listOf(firstAudio, base, secondAudio), selection.selectedTracks)
        assertEquals(listOf(firstAudio, secondAudio), selection.audioTracks)
    }

    private fun track(index: Int, mimeType: String, trackId: Int?) =
        MediaTrackDescriptor(index, mimeType, trackId)

    private companion object {
        const val VIDEO_DOLBY_VISION = "video/dolby-vision"
        const val VIDEO_HEVC = "video/hevc"
        const val VIDEO_AVC = "video/avc"
        const val AUDIO_AAC = "audio/mp4a-latm"
    }
}
