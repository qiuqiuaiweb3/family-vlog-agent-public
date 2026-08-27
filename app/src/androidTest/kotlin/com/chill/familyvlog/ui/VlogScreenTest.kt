package com.chill.familyvlog.ui

import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chill.familyvlog.MainActivity
import com.chill.familyvlog.R
import com.chill.familyvlog.input.RejectionReason
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunPhase
import com.chill.familyvlog.pipeline.RunState
import com.chill.familyvlog.subtitle.SubtitleFailureCode
import com.chill.familyvlog.subtitle.SubtitlePhase
import com.chill.familyvlog.subtitle.SubtitleRunState
import com.chill.familyvlog.ui.components.TuiActionButtonVariant
import com.chill.familyvlog.ui.components.TuiActionButtonVariantKey
import com.chill.familyvlog.ui.components.TuiStatusHasDecorationKey
import com.chill.familyvlog.ui.components.TuiStatusTone
import com.chill.familyvlog.ui.components.TuiStatusToneKey
import com.chill.familyvlog.ui.theme.FamilyVlogTuiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VlogScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext
        get() = instrumentation.targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("en"))
            }
            context.createConfigurationContext(configuration)
        }
    @Test
    fun chineseSystemStillStartsInEnglishAndEnglishCoversTheFullPage() {
        verifyLanguage("en", "zh-CN", "zh-CN")
    }

    @Test
    fun languageButtonSwitchesToSimplifiedChineseAndChineseCoversTheFullPage() {
        verifyLanguage("zh-CN", "en", "en")
    }

    @Test
    fun languageSwitchIsBidirectionalKeepsStateCallbacksAndMinimumTouchTarget() {
        val state = UiState(
            disclosureConfirmed = true,
            selectedSourceIds = listOf("video_01", "video_02"),
        )
        var pickerCalls = 0
        composeRule.setContent {
            VlogScreen(
                state = state,
                onConfirmDisclosure = {},
                onRequestPicker = { pickerCalls += 1 },
                onCreateVlog = {},
                onCancel = {},
                onOpenResult = {},
                onAddSubtitles = {},
                onCancelSubtitles = {},
                onOpenSubtitledResult = {},
                onOpenTranslationInfo = {},
            )
        }

        composeRule.onNodeWithText("CN")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText(localized("zh-CN", R.string.selected_video_count, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(localized("zh-CN", R.string.select_videos)).performClick()
        composeRule.onNodeWithText("EN")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText(localized("en", R.string.selected_video_count, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, pickerCalls) }
    }

    private fun verifyLanguage(languageTag: String, otherLanguageTag: String, systemLanguageTag: String) {
        val shownState = mutableStateOf(UiState())
        var confirmCalls = 0
        var pickerCalls = 0
        var createCalls = 0
        var cancelCalls = 0
        var openCalls = 0
        var addSubtitleCalls = 0
        var cancelSubtitleCalls = 0
        var openSubtitleCalls = 0
        var translationInfoCalls = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.Locales(ComposeLocaleList(systemLanguageTag)),
            ) {
                VlogScreen(
                    state = shownState.value,
                    onConfirmDisclosure = { confirmCalls += 1 },
                    onRequestPicker = { pickerCalls += 1 },
                    onCreateVlog = { createCalls += 1 },
                    onCancel = { cancelCalls += 1 },
                    onOpenResult = { openCalls += 1 },
                    onAddSubtitles = { addSubtitleCalls += 1 },
                    onCancelSubtitles = { cancelSubtitleCalls += 1 },
                    onOpenSubtitledResult = { openSubtitleCalls += 1 },
                    onOpenTranslationInfo = { translationInfoCalls += 1 },
                )
            }
        }

        if (languageTag == "zh-CN") {
            composeRule.onNodeWithText("CN").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("EN").assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("CN").assertIsDisplayed()
            composeRule.onNodeWithText("EN").assertDoesNotExist()
        }

        assertLocalized(languageTag, otherLanguageTag, R.string.app_name)
        assertLocalized(languageTag, otherLanguageTag, R.string.privacy_disclosure)
        assertLocalized(languageTag, otherLanguageTag, R.string.selected_video_count, 0)
        assertLocalized(languageTag, otherLanguageTag, R.string.processing_summary)
        composeRule.onNodeWithText(localized(languageTag, R.string.family_mark_short_label))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localized(languageTag, R.string.select_videos))
            .assertIsNotEnabled()
        assertLocalized(languageTag, otherLanguageTag, R.string.confirm_disclosure)
        composeRule.onNodeWithText(localized(languageTag, R.string.confirm_disclosure))
            .performClick()
        composeRule.runOnIdle { assertEquals(1, confirmCalls) }

        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                selectedSourceIds = listOf("video_01", "video_02"),
            ),
        )
        assertLocalized(languageTag, otherLanguageTag, R.string.select_videos)
        assertLocalized(languageTag, otherLanguageTag, R.string.selected_video_count, 2)
        assertLocalized(languageTag, otherLanguageTag, R.string.create_vlog)
        composeRule.onNodeWithText(localized(languageTag, R.string.select_videos)).performClick()
        composeRule.onNodeWithText(localized(languageTag, R.string.create_vlog)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, pickerCalls)
            assertEquals(1, createCalls)
        }

        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                setupError = SetupError.FIREBASE_NOT_CONFIGURED,
            ),
        )
        assertLocalized(languageTag, otherLanguageTag, R.string.setup_firebase_not_configured)

        RunPhase.entries.forEach { phase ->
            val presentation = phase.toStagePresentation()
            display(
                shownState,
                UiState(
                    disclosureConfirmed = true,
                    selectedSourceIds = listOf("video_01"),
                    runState = RunState.Active(phase),
                ),
            )
            assertLocalizedStageTrace(languageTag, otherLanguageTag, presentation)
        }
        assertLocalized(languageTag, otherLanguageTag, R.string.cancel_run)
        composeRule.onNodeWithText(localized(languageTag, R.string.cancel_run))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }

        RejectionReason.entries.forEach { reason ->
            display(
                shownState,
                UiState(
                    disclosureConfirmed = true,
                    selectedSourceIds = listOf("video_01"),
                    runState = RunState.Failed(RunPhase.PREPARING, RunFailureCode.INPUT_PREPARATION_FAILED),
                    inputRejection = reason,
                ),
            )
            assertLocalized(languageTag, otherLanguageTag, reason.resourceId())
        }

        RunFailureCode.entries.forEach { code ->
            display(
                shownState,
                UiState(
                    disclosureConfirmed = true,
                    selectedSourceIds = listOf("video_01"),
                    runState = RunState.Failed(code.phase(), code),
                ),
            )
            assertLocalized(languageTag, otherLanguageTag, code.resourceId())
        }

        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                selectedSourceIds = listOf("video_01"),
                runState = RunState.Cancelled,
            ),
        )
        assertLocalized(languageTag, otherLanguageTag, R.string.run_cancelled)

        val finalUri = Uri.parse("content://com.chill.familyvlog.test/final")
        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                selectedSourceIds = listOf("video_01"),
                runState = RunState.Succeeded,
                finalUri = finalUri,
            ),
        )
        assertLocalized(languageTag, otherLanguageTag, R.string.run_succeeded)
        assertLocalized(languageTag, otherLanguageTag, R.string.open_original_result)
        assertLocalized(languageTag, otherLanguageTag, R.string.add_subtitles_with_google)
        composeRule.onNodeWithContentDescription(
            localized(languageTag, R.string.google_translate_attribution),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_disclaimer))
            .assertDoesNotExist()
        composeRule.onNodeWithText(
            localized(languageTag, R.string.google_translate_service_information_collapsed),
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_disclaimer))
            .performScrollTo().assertIsDisplayed()
        if (languageTag == "zh-CN") {
            composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_chinese_explanation))
                .performScrollTo()
                .assertIsDisplayed()
        } else {
            composeRule.onNodeWithText(localized("zh-CN", R.string.google_translate_chinese_explanation))
                .assertDoesNotExist()
        }
        composeRule.onNodeWithText(finalUri.toString()).assertDoesNotExist()
        composeRule.onNodeWithText(localized(languageTag, R.string.open_original_result))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(localized(languageTag, R.string.add_subtitles_with_google))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_info))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(
            localized(languageTag, R.string.google_translate_service_information_expanded),
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_disclaimer))
            .assertDoesNotExist()
        composeRule.onNodeWithText(localized(languageTag, R.string.google_translate_info))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, openCalls)
            assertEquals(1, addSubtitleCalls)
            assertEquals(1, translationInfoCalls)
        }

        SubtitlePhase.entries.forEach { phase ->
            val presentation = phase.toStagePresentation()
            display(
                shownState,
                UiState(
                    disclosureConfirmed = true,
                    runState = RunState.Succeeded,
                    finalUri = finalUri,
                    subtitleRunState = SubtitleRunState.Active(phase),
                ),
            )
            assertLocalizedStageTrace(languageTag, otherLanguageTag, presentation)
        }
        assertLocalized(languageTag, otherLanguageTag, R.string.cancel_subtitles)
        composeRule.onNodeWithText(localized(languageTag, R.string.cancel_subtitles))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, cancelSubtitleCalls) }

        SubtitleFailureCode.entries.forEach { code ->
            display(
                shownState,
                UiState(
                    disclosureConfirmed = true,
                    runState = RunState.Succeeded,
                    finalUri = finalUri,
                    subtitleRunState = SubtitleRunState.Failed(code.subtitlePhase(), code),
                ),
            )
            assertLocalized(languageTag, otherLanguageTag, code.subtitleResourceId())
        }

        val subtitledUri = Uri.parse("content://com.chill.familyvlog.test/subtitled")
        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                runState = RunState.Succeeded,
                finalUri = finalUri,
                subtitleRunState = SubtitleRunState.Succeeded,
                subtitledUri = subtitledUri,
            ),
        )
        assertLocalized(languageTag, otherLanguageTag, R.string.subtitle_succeeded)
        assertLocalized(languageTag, otherLanguageTag, R.string.open_subtitled_result)
        composeRule.onNodeWithContentDescription(
            localized(languageTag, R.string.google_translate_attribution),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(subtitledUri.toString()).assertDoesNotExist()
        composeRule.onNodeWithText(localized(languageTag, R.string.open_subtitled_result))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, openSubtitleCalls)
            assertEquals(1, translationInfoCalls)
        }
    }

    @Test
    fun fiveRegionsStayInTopPrivacySelectCreateResultsOrder() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(1280.dp, 4000.dp)),
            ) {
                TestScreen(UiState())
            }
        }

        val labels = listOf(
            R.string.app_name,
            R.string.privacy_section_title,
            R.string.select_section_title,
            R.string.create_section_title,
            R.string.results_section_title,
        ).map { targetContext.getString(it) }
        val tops = labels.map { label ->
            composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        }

        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun initialAndAcknowledgedDisclosureStatesDriveControlsAndFeedback() {
        val shownState = mutableStateOf(UiState())
        composeRule.setContent { TestScreen(shownState.value) }

        composeRule.onNodeWithText(targetContext.getString(R.string.privacy_disclosure))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(targetContext.getString(R.string.confirm_disclosure))
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, TuiActionButtonVariant.Filled))
        composeRule.onNodeWithText(targetContext.getString(R.string.select_videos))
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, TuiActionButtonVariant.Outlined))
        composeRule.onNodeWithText(targetContext.getString(R.string.select_requires_disclosure))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(targetContext.getString(R.string.create_vlog))
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, TuiActionButtonVariant.Filled))
        composeRule.onNodeWithText(targetContext.getString(R.string.create_requires_disclosure))
            .performScrollTo()
            .assertIsDisplayed()

        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                selectedSourceIds = listOf("video_01", "video_02"),
            ),
        )

        composeRule.onNodeWithText(targetContext.getString(R.string.confirm_disclosure)).assertDoesNotExist()
        val acknowledged = targetContext.getString(R.string.disclosure_acknowledged)
        composeRule.onNodeWithText(acknowledged)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and hasAnyDescendant(hasText(acknowledged)),
        )
            .assertWidthIsAtLeast(200.dp)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithText(targetContext.getString(R.string.selected_video_count, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(targetContext.getString(R.string.select_videos)).assertIsEnabled()
        composeRule.onNodeWithText(targetContext.getString(R.string.create_vlog)).assertIsEnabled()
    }

    @Test
    fun taskTypeScaleIsLocalAndUsesApprovedMaterial3Values() {
        composeRule.setContent {
            FamilyVlogTuiTheme {
                TestScreen(UiState())
            }
        }

        assertTextStyle(R.string.app_name, fontSizeSp = 20, lineHeightSp = 24)
        assertTextStyle(R.string.privacy_section_title, fontSizeSp = 18, lineHeightSp = 22)
        assertTextStyle(R.string.confirm_disclosure, fontSizeSp = 16, lineHeightSp = 20)
        assertTextStyle(R.string.select_section_title, fontSizeSp = 16, lineHeightSp = 24)
        assertTextStyle(R.string.create_section_title, fontSizeSp = 16, lineHeightSp = 24)
        assertTextStyle(R.string.results_section_title, fontSizeSp = 16, lineHeightSp = 24)
        assertTextStyle(R.string.selected_video_count, 14, 20, 0)
        assertTextStyle(R.string.create_requires_disclosure, fontSizeSp = 14, lineHeightSp = 20)
        assertTextStyle(R.string.results_waiting, fontSizeSp = 14, lineHeightSp = 20)
        assertTextStyle(R.string.select_videos, fontSizeSp = 14, lineHeightSp = 20)
        assertTextStyle(R.string.create_vlog, fontSizeSp = 14, lineHeightSp = 20)
        assertTextStyle(R.string.select_requires_disclosure, fontSizeSp = 14, lineHeightSp = 20)
    }

    @Test
    fun originalRunMatrixShowsEveryStageOnlyActiveRotatesAndEveryTerminalStops() {
        val shownState = mutableStateOf(UiState(disclosureConfirmed = true, selectedSourceIds = listOf("video_01")))
        composeRule.setContent { TestScreen(shownState.value) }

        RunPhase.entries.forEach { phase ->
            val presentation = phase.toStagePresentation()
            display(shownState, shownState.value.copy(runState = RunState.Active(phase)))
            composeRule.onNodeWithText(
                targetContext.getString(
                    R.string.stage_trace_format,
                    presentation.currentStage,
                    presentation.totalStages,
                    targetContext.getString(presentation.labelRes),
                ),
            ).performScrollTo().assertIsDisplayed()
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and
                    hasAnyDescendant(hasText(targetContext.getString(R.string.working_status))),
            ).assert(SemanticsMatcher.expectValue(TuiStatusToneKey, TuiStatusTone.ActiveWarning))
                .assert(SemanticsMatcher.expectValue(TuiStatusHasDecorationKey, true))
        }

        listOf<RunState>(
            RunState.Succeeded,
            RunState.Failed(RunPhase.RENDERING, RunFailureCode.RENDER_FAILED),
            RunState.Cancelled,
        ).forEach { terminal ->
            display(shownState, shownState.value.copy(runState = terminal))
            composeRule.onAllNodes(
                SemanticsMatcher.expectValue(TuiStatusHasDecorationKey, true),
            ).assertCountEquals(0)
        }
    }

    @Test
    fun subtitleRunMatrixShowsEveryStageRetryStatesAndSeparateResult() {
        val finalUri = Uri.parse("content://test/original-sentinel")
        val shownState = mutableStateOf(
            UiState(
                disclosureConfirmed = true,
                runState = RunState.Succeeded,
                finalUri = finalUri,
            ),
        )
        composeRule.setContent { TestScreen(shownState.value) }

        SubtitlePhase.entries.forEach { phase ->
            val presentation = phase.toStagePresentation()
            display(shownState, shownState.value.copy(subtitleRunState = SubtitleRunState.Active(phase)))
            composeRule.onNodeWithText(
                targetContext.getString(
                    R.string.stage_trace_format,
                    presentation.currentStage,
                    presentation.totalStages,
                    targetContext.getString(presentation.labelRes),
                ),
            ).performScrollTo().assertIsDisplayed()
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and
                    hasAnyDescendant(hasText(targetContext.getString(R.string.working_status))),
            ).assert(SemanticsMatcher.expectValue(TuiStatusHasDecorationKey, true))
        }

        listOf<SubtitleRunState>(
            SubtitleRunState.NoSpeech,
            SubtitleRunState.Failed(SubtitlePhase.TRANSLATING, SubtitleFailureCode.TRANSLATION_FAILED),
            SubtitleRunState.Cancelled,
        ).forEach { retryable ->
            display(shownState, shownState.value.copy(subtitleRunState = retryable))
            composeRule.onNodeWithText(targetContext.getString(R.string.add_subtitles_with_google))
                .assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, TuiActionButtonVariant.GoogleOutlined))
            composeRule.onAllNodes(
                SemanticsMatcher.expectValue(TuiStatusHasDecorationKey, true),
            ).assertCountEquals(0)
        }

        val subtitledUri = Uri.parse("content://test/subtitled-sentinel")
        display(
            shownState,
            shownState.value.copy(
                subtitleRunState = SubtitleRunState.Succeeded,
                subtitledUri = subtitledUri,
            ),
        )
        composeRule.onNodeWithText(targetContext.getString(R.string.open_original_result)).assertIsEnabled()
        composeRule.onNodeWithText(targetContext.getString(R.string.open_subtitled_result)).assertIsEnabled()
    }

    @Test
    fun cancelRequestedDisablesOnlyMatchingCancelAndShowsCleanupText() {
        val finalUri = Uri.parse("content://test/original")
        val shownState = mutableStateOf(
            UiState(
                disclosureConfirmed = true,
                selectedSourceIds = listOf("video_01"),
                runState = RunState.Active(RunPhase.ANALYZING),
                runCancelRequested = true,
            ),
        )
        composeRule.setContent { TestScreen(shownState.value) }

        composeRule.onNodeWithText(targetContext.getString(R.string.cancel_run)).assertIsNotEnabled()
        val cancelling = targetContext.getString(R.string.cancelling_status)
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and hasAnyDescendant(hasText(cancelling)),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(cancelling).assertCountEquals(2)

        display(
            shownState,
            UiState(
                disclosureConfirmed = true,
                runState = RunState.Succeeded,
                finalUri = finalUri,
                subtitleRunState = SubtitleRunState.Active(SubtitlePhase.TRANSLATING),
                subtitleCancelRequested = true,
            ),
        )
        composeRule.onNodeWithText(targetContext.getString(R.string.open_original_result)).assertIsEnabled()
        composeRule.onNodeWithText(targetContext.getString(R.string.cancel_subtitles)).assertIsNotEnabled()
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(TuiStatusToneKey) and hasAnyDescendant(hasText(cancelling)),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(cancelling).assertCountEquals(2)
    }

    @Test
    fun successfulSubtitleKeepsBothResultCallbacksAndExpandableGoogleContent() {
        var originalCalls = 0
        var subtitledCalls = 0
        var googleCalls = 0
        composeRule.setContent {
            VlogScreen(
                state = UiState(
                    disclosureConfirmed = true,
                    runState = RunState.Succeeded,
                    finalUri = Uri.parse("content://test/original"),
                    subtitleRunState = SubtitleRunState.Succeeded,
                    subtitledUri = Uri.parse("content://test/subtitled"),
                ),
                onConfirmDisclosure = {},
                onRequestPicker = {},
                onCreateVlog = {},
                onCancel = {},
                onOpenResult = { originalCalls += 1 },
                onAddSubtitles = {},
                onCancelSubtitles = {},
                onOpenSubtitledResult = { subtitledCalls += 1 },
                onOpenTranslationInfo = { googleCalls += 1 },
            )
        }

        composeRule.onNodeWithText(targetContext.getString(R.string.open_original_result)).performScrollTo().performClick()
        composeRule.onNodeWithText(targetContext.getString(R.string.open_subtitled_result)).performScrollTo().performClick()
        composeRule.onNodeWithText(targetContext.getString(R.string.google_translate_disclaimer))
            .assertDoesNotExist()
        composeRule.onNodeWithText(
            targetContext.getString(R.string.google_translate_service_information_collapsed),
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(targetContext.getString(R.string.google_translate_info))
            .assert(SemanticsMatcher.expectValue(TuiActionButtonVariantKey, TuiActionButtonVariant.GoogleOutlined))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.google_translate_attribution))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(targetContext.getString(R.string.google_translate_disclaimer))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, originalCalls)
            assertEquals(1, subtitledCalls)
            assertEquals(1, googleCalls)
        }
    }

    @Test
    fun selectAndCreateUseHorizontalSplitAndOriginalEntryPrecedesSubtitleRegion() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 2000.dp)),
            ) {
                TestScreen(
                    UiState(
                        disclosureConfirmed = true,
                        selectedSourceIds = listOf("video_01"),
                        runState = RunState.Succeeded,
                        finalUri = Uri.parse("content://test/original"),
                    ),
                )
            }
        }

        val selectHeader = composeRule.onNodeWithText(
            targetContext.getString(R.string.select_section_title),
        ).fetchSemanticsNode().boundsInRoot
        val selectButton = composeRule.onNodeWithText(
            targetContext.getString(R.string.select_videos),
        ).fetchSemanticsNode().boundsInRoot
        val createHeader = composeRule.onNodeWithText(
            targetContext.getString(R.string.create_section_title),
        ).fetchSemanticsNode().boundsInRoot
        val originalButton = composeRule.onNodeWithText(
            targetContext.getString(R.string.open_original_result),
        ).fetchSemanticsNode().boundsInRoot
        val subtitleHeader = composeRule.onNodeWithText(
            targetContext.getString(R.string.results_section_title),
        ).fetchSemanticsNode().boundsInRoot
        val subtitleButton = composeRule.onNodeWithText(
            targetContext.getString(R.string.add_subtitles_with_google),
        ).fetchSemanticsNode().boundsInRoot

        assertTrue(selectHeader.left < selectButton.left)
        assertTrue(createHeader.left < originalButton.left)
        assertTrue(originalButton.top < subtitleHeader.top)
        assertTrue(subtitleButton.top > subtitleHeader.top)
    }

    @Test
    fun googleInformationExpansionSurvivesLanguageSwitchAndCanCollapse() {
        composeRule.setContent {
            TestScreen(
                UiState(
                    disclosureConfirmed = true,
                    runState = RunState.Succeeded,
                    finalUri = Uri.parse("content://test/original"),
                ),
            )
        }

        composeRule.onNodeWithText(targetContext.getString(R.string.google_translate_disclaimer))
            .assertDoesNotExist()
        composeRule.onNodeWithText(
            targetContext.getString(R.string.google_translate_service_information_collapsed),
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(targetContext.getString(R.string.google_translate_disclaimer))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CN").performScrollTo().performClick()
        composeRule.onNodeWithText(
            localized("zh-CN", R.string.google_translate_service_information_expanded),
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(localized("zh-CN", R.string.google_translate_disclaimer))
            .assertDoesNotExist()
    }

    @Test
    fun officialGoogleDisclaimerRemainsExactInBothLocales() {
        assertEquals(
            OFFICIAL_GOOGLE_TRANSLATE_DISCLAIMER,
            localized("en", R.string.google_translate_disclaimer),
        )
        assertEquals(
            OFFICIAL_GOOGLE_TRANSLATE_DISCLAIMER,
            localized("zh-CN", R.string.google_translate_disclaimer),
        )
    }

    @Test
    fun screenDoesNotExposeUrisPercentagesEtaOrProgressSemantics() {
        val original = "content://private/original-sentinel"
        val subtitled = "content://private/subtitled-sentinel"
        composeRule.setContent {
            TestScreen(
                UiState(
                    disclosureConfirmed = true,
                    runState = RunState.Succeeded,
                    finalUri = Uri.parse(original),
                    subtitleRunState = SubtitleRunState.Succeeded,
                    subtitledUri = Uri.parse(subtitled),
                ),
            )
        }

        composeRule.onNodeWithText(original).assertDoesNotExist()
        composeRule.onNodeWithText(subtitled).assertDoesNotExist()
        composeRule.onNodeWithText("99%", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("ETA").assertDoesNotExist()
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).assertCountEquals(0)
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(state: UiState) {
        VlogScreen(
            state = state,
            onConfirmDisclosure = {},
            onRequestPicker = {},
            onCreateVlog = {},
            onCancel = {},
            onOpenResult = {},
            onAddSubtitles = {},
            onCancelSubtitles = {},
            onOpenSubtitledResult = {},
            onOpenTranslationInfo = {},
        )
    }

    private fun display(state: MutableState<UiState>, value: UiState) {
        composeRule.runOnIdle { state.value = value }
        composeRule.waitForIdle()
    }

    private fun assertLocalized(
        languageTag: String,
        otherLanguageTag: String,
        @StringRes resourceId: Int,
        vararg formatArgs: Any,
    ) {
        val expected = localized(languageTag, resourceId, *formatArgs)
        val other = localized(otherLanguageTag, resourceId, *formatArgs)
        assertNotEquals(expected, other)
        composeRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(other).assertDoesNotExist()
    }

    private fun assertLocalizedStageTrace(
        languageTag: String,
        otherLanguageTag: String,
        presentation: StagePresentation,
    ) {
        val expected = localized(
            languageTag,
            R.string.stage_trace_format,
            presentation.currentStage,
            presentation.totalStages,
            localized(languageTag, presentation.labelRes),
        )
        val other = localized(
            otherLanguageTag,
            R.string.stage_trace_format,
            presentation.currentStage,
            presentation.totalStages,
            localized(otherLanguageTag, presentation.labelRes),
        )
        assertNotEquals(expected, other)
        composeRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(other).assertDoesNotExist()
    }

    private fun localized(
        languageTag: String,
        @StringRes resourceId: Int,
        vararg formatArgs: Any,
    ): String {
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        return targetContext.createConfigurationContext(configuration).getString(resourceId, *formatArgs)
    }

    private fun assertTextStyle(
        @StringRes resourceId: Int,
        fontSizeSp: Int,
        lineHeightSp: Int,
        vararg formatArgs: Any,
    ) {
        val textLayoutResults = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(
            targetContext.getString(resourceId, *formatArgs),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action

        assertEquals(true, action?.invoke(textLayoutResults))
        val textStyle = textLayoutResults.single().layoutInput.style
        assertEquals(fontSizeSp.sp, textStyle.fontSize)
        assertEquals(lineHeightSp.sp, textStyle.lineHeight)
    }

    @StringRes
    private fun RejectionReason.resourceId(): Int = when (this) {
        RejectionReason.UNREADABLE -> R.string.rejection_unreadable
        RejectionReason.NON_POSITIVE_DURATION -> R.string.rejection_non_positive_duration
        RejectionReason.NO_VIDEO_TRACK -> R.string.rejection_no_video_track
        RejectionReason.VIDEO_DECODE_FAILED -> R.string.rejection_video_decode_failed
    }

    @StringRes
    private fun RunFailureCode.resourceId(): Int = when (this) {
        RunFailureCode.INPUT_PREPARATION_FAILED -> R.string.failure_input_preparation
        RunFailureCode.ANALYSIS_INPUT_TOO_LARGE -> R.string.failure_analysis_input_too_large
        RunFailureCode.UNDERSTANDING_FAILED -> R.string.failure_understanding
        RunFailureCode.EDITING_FAILED -> R.string.failure_editing
        RunFailureCode.PRIVATE_STORAGE_FAILED -> R.string.failure_private_storage
        RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED -> R.string.failure_atomic_move_unsupported
        RunFailureCode.RENDER_FAILED -> R.string.failure_render
        RunFailureCode.OUTPUT_INSPECTION_FAILED -> R.string.failure_output_inspection
        RunFailureCode.PUBLISH_FAILED -> R.string.failure_publish
    }

    private fun RunFailureCode.phase(): RunPhase = when (this) {
        RunFailureCode.INPUT_PREPARATION_FAILED,
        RunFailureCode.ANALYSIS_INPUT_TOO_LARGE,
        -> RunPhase.PREPARING
        RunFailureCode.UNDERSTANDING_FAILED -> RunPhase.ANALYZING
        RunFailureCode.EDITING_FAILED,
        RunFailureCode.PRIVATE_STORAGE_ATOMIC_MOVE_UNSUPPORTED,
        -> RunPhase.PLANNING
        RunFailureCode.RENDER_FAILED,
        RunFailureCode.OUTPUT_INSPECTION_FAILED,
        -> RunPhase.RENDERING
        RunFailureCode.PRIVATE_STORAGE_FAILED,
        RunFailureCode.PUBLISH_FAILED,
        -> RunPhase.SAVING
    }

    @StringRes
    private fun SubtitleFailureCode.subtitleResourceId(): Int = when (this) {
        SubtitleFailureCode.TRANSCRIPTION_FAILED -> R.string.subtitle_failure_transcription
        SubtitleFailureCode.MODEL_DOWNLOAD_FAILED -> R.string.subtitle_failure_model_download
        SubtitleFailureCode.TRANSLATION_FAILED -> R.string.subtitle_failure_translation
        SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED -> R.string.subtitle_failure_layout
        SubtitleFailureCode.PRIVATE_STORAGE_FAILED -> R.string.subtitle_failure_private_storage
        SubtitleFailureCode.RENDER_FAILED -> R.string.subtitle_failure_render
        SubtitleFailureCode.OUTPUT_INSPECTION_FAILED -> R.string.subtitle_failure_output_inspection
        SubtitleFailureCode.PUBLISH_FAILED -> R.string.subtitle_failure_publish
    }

    private fun SubtitleFailureCode.subtitlePhase(): SubtitlePhase = when (this) {
        SubtitleFailureCode.TRANSCRIPTION_FAILED -> SubtitlePhase.TRANSCRIBING
        SubtitleFailureCode.MODEL_DOWNLOAD_FAILED -> SubtitlePhase.DOWNLOADING_MODEL
        SubtitleFailureCode.TRANSLATION_FAILED,
        SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED,
        -> SubtitlePhase.TRANSLATING
        SubtitleFailureCode.PRIVATE_STORAGE_FAILED,
        SubtitleFailureCode.PUBLISH_FAILED,
        -> SubtitlePhase.SAVING
        SubtitleFailureCode.RENDER_FAILED,
        SubtitleFailureCode.OUTPUT_INSPECTION_FAILED,
        -> SubtitlePhase.RENDERING
    }

    private companion object {
        const val OFFICIAL_GOOGLE_TRANSLATE_DISCLAIMER =
            "THIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. " +
                "GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, " +
                "INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF " +
                "MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT."
    }
}

@RunWith(AndroidJUnit4::class)
class MainActivityViewModelRetentionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun configurationChangeRetainsDisclosureWithoutRestartingTheScreenState() {
        val englishConfiguration = Configuration(composeRule.activity.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en"))
        }
        val confirm = composeRule.activity.createConfigurationContext(englishConfiguration)
            .getString(R.string.confirm_disclosure)
        composeRule.onNodeWithText(confirm).performClick()
        composeRule.onNodeWithText("CN").performClick()
        composeRule.onNodeWithText("EN").assertIsDisplayed()
        composeRule.waitForIdle()
        val retained = ViewModelProvider(composeRule.activity)[MainViewModel::class.java]

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertSame(retained, ViewModelProvider(composeRule.activity)[MainViewModel::class.java])
        composeRule.onNodeWithText(confirm).assertDoesNotExist()
        composeRule.onNodeWithText("EN").assertIsDisplayed()
        val configuration = Configuration(composeRule.activity.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("zh-CN"))
        }
        val select = composeRule.activity.createConfigurationContext(configuration)
            .getString(R.string.select_videos)
        composeRule.onNodeWithText(select).assertIsEnabled()
    }
}
