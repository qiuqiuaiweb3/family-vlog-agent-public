package com.chill.familyvlog.render

import android.media.MediaFormat
import android.net.Uri
import com.chill.familyvlog.contract.EditClip
import com.chill.familyvlog.contract.ValidatedEditClip
import com.chill.familyvlog.contract.ValidatedEditPlan
import com.chill.familyvlog.input.AudioTrackInfo
import com.chill.familyvlog.input.ProbeResult
import com.chill.familyvlog.input.SelectedSource
import com.chill.familyvlog.input.VideoTrackInfo
import com.chill.familyvlog.pipeline.PipelineException
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

class RenderSpecFactoryTest {
    @Test
    fun `single clip keeps uri exact times and first canvas`() {
        val uri = mock(Uri::class.java)
        val spec = buildRenderSpec(
            plan(clip(sourceId = "video_01", start = "9", end = "10.000", executionEnd = "9.9996")),
            sources(source("video_01", uri)),
            probes("video_01" to probe(width = 1080, height = 1920)),
        )

        assertEquals(1, spec.clips.size)
        assertSame(uri, spec.clips.single().uri)
        assertEquals(9_000_000L, spec.clips.single().startUs)
        assertEquals(9_999_600L, spec.clips.single().endUs)
        assertFalse(spec.clips.single().hasAudio)
        assertFalse(spec.expectsAudio)
        assertEquals(9f / 16f, spec.canvasAspectRatio, 0.000001f)
    }

    @Test
    fun `execution first clip and its rotation determine canvas while plan order is preserved`() {
        val landscapeUri = mock(Uri::class.java)
        val rotatedUri = mock(Uri::class.java)
        val spec = buildRenderSpec(
            plan(
                clip(sourceId = "video_02", eventId = "rotated", start = "0", end = "1"),
                clip(sourceId = "video_01", eventId = "landscape", start = "0", end = "1"),
            ),
            sources(
                source("video_01", landscapeUri),
                source("video_02", rotatedUri),
            ),
            probes(
                "video_01" to probe(width = 1920, height = 1080),
                "video_02" to probe(width = 1920, height = 1080, rotationDegrees = 90, withAudio = true),
            ),
        )

        assertSame(rotatedUri, spec.clips[0].uri)
        assertSame(landscapeUri, spec.clips[1].uri)
        assertEquals(listOf(true, false), spec.clips.map(RenderClip::hasAudio))
        assertTrue(spec.expectsAudio)
        assertEquals(9f / 16f, spec.canvasAspectRatio, 0.000001f)
    }

    @Test
    fun `repeated reverse and overlapping clips remain executable`() {
        val uri = mock(Uri::class.java)
        val spec = buildRenderSpec(
            plan(
                clip(eventId = "later", start = "3", end = "5"),
                clip(eventId = "earlier", start = "1", end = "4"),
                clip(eventId = "earlier", start = "1", end = "4"),
            ),
            sources(source(uri = uri)),
            probes("video_01" to probe()),
        )

        assertEquals(listOf(3_000_000L, 1_000_000L, 1_000_000L), spec.clips.map(RenderClip::startUs))
        spec.clips.forEach { assertSame(uri, it.uri) }
    }

    @Test
    fun `hdr first followed by sdr fails while supported and unknown orders remain executable`() {
        val firstUri = mock(Uri::class.java)
        val secondUri = mock(Uri::class.java)
        val twoClips = plan(
            clip(sourceId = "video_01", start = "0", end = "1"),
            clip(sourceId = "video_02", start = "0", end = "1"),
        )
        val mappedSources = sources(
            source("video_01", firstUri),
            source("video_02", secondUri),
        )

        assertRenderFailure(
            twoClips,
            mappedSources,
            probes(
                "video_01" to probe(colorTransfer = MediaFormat.COLOR_TRANSFER_HLG),
                "video_02" to probe(colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
            ),
        )

        listOf(
            MediaFormat.COLOR_TRANSFER_HLG to MediaFormat.COLOR_TRANSFER_HLG,
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO to MediaFormat.COLOR_TRANSFER_HLG,
            MediaFormat.COLOR_TRANSFER_HLG to null,
            null to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        ).forEach { (firstTransfer, secondTransfer) ->
            buildRenderSpec(
                twoClips,
                mappedSources,
                probes(
                    "video_01" to probe(colorTransfer = firstTransfer),
                    "video_02" to probe(colorTransfer = secondTransfer),
                ),
            )
        }
        buildRenderSpec(
            plan(clip(sourceId = "video_01", start = "0", end = "1")),
            sources(source("video_01", firstUri)),
            probes("video_01" to probe(colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084)),
        )
    }

    @Test
    fun `more than one video or audio track fails without guessing`() {
        val uri = mock(Uri::class.java)
        val unreferencedUri = mock(Uri::class.java)
        val mappedSources = sources(source(uri = uri))
        val validPlan = plan(clip())

        assertRenderFailure(
            validPlan,
            mappedSources,
            probes("video_01" to probe(videoTrackCount = 2)),
        )
        assertRenderFailure(
            validPlan,
            mappedSources,
            probes("video_01" to probe(withAudio = true, audioTrackCount = 2)),
        )
        assertRenderFailure(
            validPlan,
            sources(
                source("video_01", uri),
                source("video_02", unreferencedUri),
            ),
            probes(
                "video_01" to probe(),
                "video_02" to probe(videoTrackCount = 2),
            ),
        )
    }

    @Test
    fun `plan longer than forty five seconds keeps every executable clip`() {
        val uri = mock(Uri::class.java)
        val clips = (0 until 46).map { second ->
            clip(
                eventId = "event_${second + 1}",
                start = second.toString(),
                end = (second + 1).toString(),
            )
        }
        val validatedPlan = plan(*clips.toTypedArray())

        val spec = buildRenderSpec(
            validatedPlan,
            sources(source(uri = uri)),
            probes("video_01" to probe(durationUs = 46_000_000L)),
        )

        assertEquals(BigDecimal("46"), validatedPlan.totalDuration)
        assertEquals(46, spec.clips.size)
        assertEquals(46_000_000L, spec.clips.sumOf { it.endUs - it.startUs })
        assertEquals(
            (0 until 46).map { second -> second * 1_000_000L to (second + 1) * 1_000_000L },
            spec.clips.map { it.startUs to it.endUs },
        )
    }

    @Test
    fun `exact microsecond and long max boundaries are accepted`() {
        val uri = mock(Uri::class.java)
        val clips = listOf(
            clip(start = "0.000001", end = "0.000002"),
            clip(start = "9223372036854.775806", end = "9223372036854.775807"),
        )

        val spec = buildRenderSpec(
            plan(*clips.toTypedArray()),
            sources(source(uri = uri)),
            probes("video_01" to probe(durationUs = Long.MAX_VALUE)),
        )

        assertEquals(1L, spec.clips[0].startUs)
        assertEquals(2L, spec.clips[0].endUs)
        assertEquals(Long.MAX_VALUE - 1, spec.clips[1].startUs)
        assertEquals(Long.MAX_VALUE, spec.clips[1].endUs)
    }

    @Test
    fun `missing mappings invalid ranges dimensions rotation submicroseconds and overflow fail closed`() {
        val uri = mock(Uri::class.java)
        val validSource = source(uri = uri)
        val validProbe = "video_01" to probe()

        assertRenderFailure(plan(clip()), emptyMap(), probes(validProbe))
        assertRenderFailure(plan(clip()), sources(validSource), emptyMap())
        assertRenderFailure(plan(clip()), sources(validSource.copy(sourceId = "other")), probes(validProbe))
        assertRenderFailure(ValidatedEditPlan(emptyList(), BigDecimal.ZERO), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe().copy(readable = false)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe().copy(durationUs = 0)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe().copy(durationUs = null)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe().copy(videoTrack = null)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe(width = null)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe(height = 0)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe(rotationDegrees = 45)))
        assertRenderFailure(plan(clip()), sources(validSource), probes("video_01" to probe().copy(firstSyncFrameDecoded = false)))
        assertRenderFailure(plan(clip(start = "-1", end = "1")), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip(start = "1", end = "2", executionEnd = "1")), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip(start = "9", end = "11")), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip(start = "0.0000001", end = "1")), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip(start = "0", end = "0.0000001")), sources(validSource), probes(validProbe))
        assertRenderFailure(plan(clip(start = "0", end = "9223372036854.775808")), sources(validSource), probes(validProbe))
    }

    private fun assertRenderFailure(
        plan: ValidatedEditPlan,
        sources: Map<String, SelectedSource>,
        probes: Map<String, ProbeResult>,
    ) {
        try {
            buildRenderSpec(plan, sources, probes)
            fail("Expected render failure")
        } catch (failure: PipelineException) {
            assertEquals(RunPhase.RENDERING, failure.phase)
            assertEquals(RunFailureCode.RENDER_FAILED, failure.code)
        }
    }

    private fun plan(vararg clips: ValidatedEditClip) = ValidatedEditPlan(
        clips = clips.toList(),
        totalDuration = clips.fold(BigDecimal.ZERO) { total, clip ->
            total.add(clip.executionEnd.subtract(clip.clip.start))
        },
    )

    private fun clip(
        sourceId: String = "video_01",
        eventId: String = "event_01",
        start: String = "1",
        end: String = "2",
        executionEnd: String = end,
    ): ValidatedEditClip = ValidatedEditClip(
        clip = EditClip(
            sourceId = sourceId,
            eventId = eventId,
            start = BigDecimal(start),
            end = BigDecimal(end),
            storyRole = "development",
            selectionReason = "fixture",
        ),
        executionEnd = BigDecimal(executionEnd),
    )

    private fun source(sourceId: String = "video_01", uri: Uri) = SelectedSource(1, sourceId, uri)

    private fun sources(vararg sources: SelectedSource) = sources.associateBy(SelectedSource::sourceId)

    private fun probes(vararg probes: Pair<String, ProbeResult>) = mapOf(*probes)

    private fun probe(
        withAudio: Boolean = false,
        audioTrackCount: Int = if (withAudio) 1 else 0,
        durationUs: Long? = 10_000_000L,
        width: Int? = 1080,
        height: Int? = 1920,
        rotationDegrees: Int? = 0,
        colorTransfer: Int? = null,
        videoTrackCount: Int = 1,
    ) = ProbeResult(
        readable = true,
        durationUs = durationUs,
        resolverMimeType = "video/mp4",
        containerMimeType = "video/mp4",
        videoTrack = VideoTrackInfo(
            mimeType = "video/avc",
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            frameRate = 30f,
            colorStandard = null,
            colorTransfer = colorTransfer,
            colorRange = null,
        ),
        audioTracks = List(audioTrackCount) { AudioTrackInfo("audio/mp4a-latm", 2, 48_000) },
        firstSyncFrameDecoded = true,
        videoTrackCount = videoTrackCount,
    )
}
