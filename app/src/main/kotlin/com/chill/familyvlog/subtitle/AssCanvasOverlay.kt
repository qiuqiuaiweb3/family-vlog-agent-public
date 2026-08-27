package com.chill.familyvlog.subtitle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack

@UnstableApi
class AssCanvasOverlay(
    assDocument: ByteArray,
    fontBytes: ByteArray,
    private val expectedWidth: Int,
    private val expectedHeight: Int,
) : CanvasOverlay(true) {
    private val ass: Ass
    private val track: AssTrack
    private val render: AssRender
    private var released = false

    init {
        require(
            assDocument.isNotEmpty() &&
                fontBytes.isNotEmpty() &&
                expectedWidth > 0 &&
                expectedHeight > 0,
        )
        val createdAss = Ass()
        var createdTrack: AssTrack? = null
        var createdRender: AssRender? = null
        try {
            createdAss.addFont(SUBTITLE_FONT_NAME, fontBytes)
            createdTrack = createdAss.createTrack()
            createdTrack.readBuffer(assDocument)
            require(createdTrack.getWidth() > 0 && createdTrack.getHeight() > 0)
            createdRender = createdAss.createRender()
            createdRender.setTrack(createdTrack)
        } catch (failure: Throwable) {
            listOfNotNull(
                createdRender?.let { render -> { render.release() } },
                createdTrack?.let { track -> { track.release() } },
                { createdAss.release() },
            ).forEach { cleanup ->
                try {
                    cleanup()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        ass = createdAss
        track = requireNotNull(createdTrack)
        render = requireNotNull(createdRender)
    }

    override fun configure(videoSize: Size) {
        require(videoSize.width == expectedWidth && videoSize.height == expectedHeight)
        super.configure(videoSize)
        render.setStorageSize(videoSize.width, videoSize.height)
        render.setFrameSize(videoSize.width, videoSize.height)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        check(!released && presentationTimeUs >= 0)
        val frame = render.renderFrame(presentationTimeUs / 1_000L, AssTexType.BITMAP_RGBA)
        if (frame == null) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            return
        }
        if (frame.changed == 0) return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        frame.images.orEmpty().forEach { image ->
            image.bitmap?.let { bitmap -> canvas.drawBitmap(bitmap, image.x.toFloat(), image.y.toFloat(), null) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        var failure: Throwable? = null
        fun releasePart(block: () -> Unit) {
            try {
                block()
            } catch (current: Throwable) {
                if (failure == null) failure = current else failure?.addSuppressed(current)
            }
        }
        releasePart { render.release() }
        releasePart { track.release() }
        releasePart { ass.release() }
        releasePart { super.release() }
        failure?.let { throw VideoFrameProcessingException(it) }
    }
}

private const val SUBTITLE_FONT_NAME = "NotoSansCJKsc-Bold.otf"
