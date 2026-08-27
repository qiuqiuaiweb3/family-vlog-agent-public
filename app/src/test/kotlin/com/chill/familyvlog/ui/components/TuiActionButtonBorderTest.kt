package com.chill.familyvlog.ui.components

import androidx.compose.ui.graphics.SolidColor
import com.chill.familyvlog.ui.theme.TuiBorderWidth
import com.chill.familyvlog.ui.theme.TuiDisabledContent
import com.chill.familyvlog.ui.theme.TuiPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TuiActionButtonBorderTest {
    @Test
    fun filledDisabledUsesDisabledOutline() {
        val border = filledButtonBorder(enabled = false, pressed = false)

        assertNotNull(border)
        assertEquals(TuiBorderWidth, border?.width)
        assertEquals(SolidColor(TuiDisabledContent), border?.brush)
    }

    @Test
    fun filledEnabledNotPressedHasNoOutline() {
        assertNull(filledButtonBorder(enabled = true, pressed = false))
    }

    @Test
    fun filledEnabledPressedUsesPrimaryOutline() {
        val border = filledButtonBorder(enabled = true, pressed = true)

        assertNotNull(border)
        assertEquals(TuiBorderWidth, border?.width)
        assertEquals(SolidColor(TuiPrimary), border?.brush)
    }
}
