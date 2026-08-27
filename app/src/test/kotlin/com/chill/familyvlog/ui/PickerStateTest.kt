package com.chill.familyvlog.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerStateTest {
    @Test
    fun `selection request requires disclosure before it can launch picker`() {
        val initial = PickerState()

        assertEquals(PickerAction.ShowDisclosure, initial.requestVideoPicker())
        assertEquals(PickerAction.LaunchVideoPicker, initial.confirmDisclosure().requestVideoPicker())
    }
}
