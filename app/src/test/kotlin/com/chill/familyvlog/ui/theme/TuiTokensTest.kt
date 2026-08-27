package com.chill.familyvlog.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.toArgb

class TuiTokensTest {
    @Test
    fun colorsUseFrozenArgbValues() {
        assertEquals(0xFF00060C.toInt(), TuiBackground.toArgb())
        assertEquals(0xFF010D11.toInt(), TuiPanel.toArgb())
        assertEquals(0xFF051F36.toInt(), TuiPanelRaised.toArgb())
        assertEquals(0xFF2AE4BC.toInt(), TuiPrimary.toArgb())
        assertEquals(0xFFF2B636.toInt(), TuiActiveWarning.toArgb())
        assertEquals(0xFF9668FF.toInt(), TuiGoogle.toArgb())
        assertEquals(0xFFF8F9FA.toInt(), TuiGoogleAttributionSurface.toArgb())
        assertEquals(0xFFFF5C6C.toInt(), TuiFailure.toArgb())
        assertEquals(0xFFE8E0D5.toInt(), TuiTextPrimary.toArgb())
        assertEquals(0xFF7EDDC3.toInt(), TuiTextSecondary.toArgb())
        assertEquals(0xFF2AE4BC.toInt(), TuiBorder.toArgb())
        assertEquals(0xFF0B243F.toInt(), TuiDisabledContainer.toArgb())
        assertEquals(0xFF6B817E.toInt(), TuiDisabledContent.toArgb())
    }

    @Test
    fun shapeStrokeAndTouchTokensUseFrozenDpValues() {
        assertEquals(4f, TuiPanelCorner.value)
        assertEquals(8f, TuiButtonCorner.value)
        assertEquals(1f, TuiBorderWidth.value)
        assertEquals(1f, TuiPressedTranslationY.value)
        assertEquals(48f, TuiMinimumInteractiveSize.value)
    }

    @Test
    fun spacingUsesFrozenDpValues() {
        assertEquals(4f, TuiSpacingXs.value)
        assertEquals(6f, TuiSpacingSm.value)
        assertEquals(8f, TuiSpacingSection.value)
        assertEquals(12f, TuiSpacingMd.value)
        assertEquals(16f, TuiSpacingLg.value)
        assertEquals(24f, TuiSpacingXl.value)
    }

    @Test
    fun taskTypeScaleUsesApprovedSpValues() {
        assertEquals(16f, TuiTaskTitleFontSize.value)
        assertEquals(24f, TuiTaskTitleLineHeight.value)
        assertEquals(14f, TuiTaskContentFontSize.value)
        assertEquals(20f, TuiTaskContentLineHeight.value)
    }

    @Test
    fun gearRotationDurationUsesFrozenMilliseconds() {
        assertEquals(1_200, TuiGearRotationDurationMillis)
    }
}
