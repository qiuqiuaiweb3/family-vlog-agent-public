package com.chill.familyvlog.ui.components

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.R
import com.chill.familyvlog.ui.theme.FamilyVlogTuiTheme
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TuiComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allButtonVariantsExposeEnabledAndDisabledMaterialSemantics() {
        composeRule.setContent {
            FamilyVlogTuiTheme {
                Column {
                    TuiActionButtonVariant.entries.forEach { variant ->
                        TuiActionButton(onClick = {}, variant = variant) {
                            Text("enabled-$variant")
                        }
                        TuiActionButton(onClick = {}, enabled = false, variant = variant) {
                            Text("disabled-$variant")
                        }
                    }
                }
            }
        }

        TuiActionButtonVariant.entries.forEach { variant ->
            composeRule.onNodeWithText("enabled-$variant")
                .assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, variant))
            composeRule.onNodeWithText("disabled-$variant")
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, variant))
        }
    }

    @Test
    fun disabledButtonShowsSupportingTextAndDoesNotClick() {
        var clicks = 0
        composeRule.setContent {
            FamilyVlogTuiTheme {
                TuiActionButton(
                    onClick = { clicks += 1 },
                    enabled = false,
                    supportingText = "Choose videos first",
                ) { Text("Create") }
            }
        }

        composeRule.onNodeWithText("Choose videos first").assertIsDisplayed()
        composeRule.onNodeWithText("Create").performClick()
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun buttonProvidesExplicitMinimumInteractiveSize() {
        composeRule.setContent {
            FamilyVlogTuiTheme {
                TuiActionButton(
                    onClick = {},
                    modifier = Modifier.testTag("button"),
                ) { Text("Go") }
            }
        }

        composeRule.onNodeWithTag("button")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun injectedInteractionSourceReportsPressReleaseAndOneQuickClick() {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        var clicks = 0
        composeRule.setContent {
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect(interactions::add)
            }
            FamilyVlogTuiTheme {
                TuiActionButton(
                    onClick = { clicks += 1 },
                    interactionSource = interactionSource,
                    modifier = Modifier.testTag("button"),
                ) { Text("Run") }
            }
        }

        composeRule.onNodeWithTag("button").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(interactions.any { it is PressInteraction.Press })
            assertTrue(interactions.any { it is PressInteraction.Release })
            assertEquals(1, clicks)
        }
    }

    @Test
    fun heldPressPublishesPressBeforeReleaseAndClicksExactlyOnceAfterRelease() {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        var clicks = 0
        composeRule.setContent {
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect(interactions::add)
            }
            FamilyVlogTuiTheme {
                TuiActionButton(
                    onClick = { clicks += 1 },
                    interactionSource = interactionSource,
                    modifier = Modifier.testTag("held-button"),
                ) { Text("Hold") }
            }
        }

        composeRule.onNodeWithTag("held-button").performTouchInput { down(center) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(interactions.any { it is PressInteraction.Press })
            assertFalse(interactions.any { it is PressInteraction.Release })
            assertEquals(0, clicks)
        }

        composeRule.onNodeWithTag("held-button").performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val press = interactions.filterIsInstance<PressInteraction.Press>().single()
            assertTrue(interactions.any { it is PressInteraction.Release && it.press === press })
            assertEquals(1, clicks)
        }
    }

    @Test
    fun statusLineExposesToneAndDecorationSelectionWithoutDecorativeChildSemantics() {
        composeRule.setContent {
            FamilyVlogTuiTheme {
                StatusLine(
                    text = "Working",
                    tone = TuiStatusTone.ActiveWarning,
                    decoration = { RotatingGear() },
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and hasAnyDescendant(hasText("Working")),
        )
            .assert(SemanticsMatcher.expectValue(TuiStatusToneKey, TuiStatusTone.ActiveWarning))
            .assert(SemanticsMatcher.expectValue(TuiStatusHasDecorationKey, true))
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun stageTraceUsesExplicitNumbersResourceTextAndPoliteLiveRegion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.phase_analyzing)
        val expected = context.getString(R.string.stage_trace_format, 2, 5, label)
        composeRule.setContent {
            FamilyVlogTuiTheme {
                StageTrace(
                    currentStage = 2,
                    totalStages = 5,
                    stageLabelRes = R.string.phase_analyzing,
                )
            }
        }

        composeRule.onNodeWithText(expected).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun rotatingGearAddsNoAccessibleNodeOrLiveRegion() {
        composeRule.setContent {
            FamilyVlogTuiTheme {
                RotatingGear()
            }
        }

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun familyMarkIsComposableInResponsiveFourByThreeBounds() {
        val description = "Family mark"
        composeRule.setContent {
            FamilyVlogTuiTheme {
                FamilyMark(
                    modifier = Modifier.testTag("family-mark"),
                    contentDescription = description,
                )
            }
        }

        composeRule.onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(36.dp)
    }
}
