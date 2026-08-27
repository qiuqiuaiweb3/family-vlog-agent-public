package com.chill.familyvlog.input

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPickerRequestTest {
    @Test
    fun `activity default is image and video without ordered selection`() {
        val defaultRequest = PickVisualMediaRequest.Builder().build()

        assertEquals(ActivityResultContracts.PickVisualMedia.ImageAndVideo, defaultRequest.mediaType)
        assertFalse(defaultRequest.isOrderedSelection)
    }

    @Test
    fun `video picker request limits media to videos and requests ordered selection`() {
        val request = buildVideoPickerRequest()

        assertEquals(ActivityResultContracts.PickVisualMedia.VideoOnly, request.mediaType)
        assertTrue(request.isOrderedSelection)
    }
}
