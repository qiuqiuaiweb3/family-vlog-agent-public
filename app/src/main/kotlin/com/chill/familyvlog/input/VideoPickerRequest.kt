package com.chill.familyvlog.input

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

fun buildVideoPickerRequest(): PickVisualMediaRequest = PickVisualMediaRequest.Builder()
    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
    .setOrderedSelection(true)
    .build()
