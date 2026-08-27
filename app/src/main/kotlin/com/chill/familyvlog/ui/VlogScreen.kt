package com.chill.familyvlog.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.chill.familyvlog.R
import com.chill.familyvlog.input.RejectionReason
import com.chill.familyvlog.pipeline.RunFailureCode
import com.chill.familyvlog.pipeline.RunState
import com.chill.familyvlog.subtitle.SubtitleFailureCode
import com.chill.familyvlog.subtitle.SubtitleRunState
import com.chill.familyvlog.ui.components.FamilyMark
import com.chill.familyvlog.ui.components.RotatingGear
import com.chill.familyvlog.ui.components.StageTrace
import com.chill.familyvlog.ui.components.StatusLine
import com.chill.familyvlog.ui.components.TuiActionButton
import com.chill.familyvlog.ui.components.TuiActionButtonVariant
import com.chill.familyvlog.ui.components.TuiPanel
import com.chill.familyvlog.ui.components.TuiStatusTone
import com.chill.familyvlog.ui.theme.TuiBorder
import com.chill.familyvlog.ui.theme.TuiBorderWidth
import com.chill.familyvlog.ui.theme.TuiGoogleAttributionSurface
import com.chill.familyvlog.ui.theme.TuiMinimumInteractiveSize
import com.chill.familyvlog.ui.theme.TuiPanelCorner
import com.chill.familyvlog.ui.theme.TuiPanelRaised
import com.chill.familyvlog.ui.theme.TuiPrimary
import com.chill.familyvlog.ui.theme.TuiSpacingMd
import com.chill.familyvlog.ui.theme.TuiSpacingSection
import com.chill.familyvlog.ui.theme.TuiSpacingSm
import com.chill.familyvlog.ui.theme.TuiSpacingXs
import com.chill.familyvlog.ui.theme.TuiTaskContentFontSize
import com.chill.familyvlog.ui.theme.TuiTaskContentLineHeight
import com.chill.familyvlog.ui.theme.TuiTaskTitleFontSize
import com.chill.familyvlog.ui.theme.TuiTaskTitleLineHeight
import com.chill.familyvlog.ui.theme.TuiTextSecondary

@Composable
fun VlogScreen(
    state: UiState,
    onConfirmDisclosure: () -> Unit,
    onRequestPicker: () -> Unit,
    onCreateVlog: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: () -> Unit,
    onAddSubtitles: () -> Unit,
    onCancelSubtitles: () -> Unit,
    onOpenSubtitledResult: () -> Unit,
    onOpenTranslationInfo: () -> Unit,
) {
    var languageTag by rememberSaveable { mutableStateOf(ENGLISH_LANGUAGE_TAG) }
    var googleInformationExpanded by rememberSaveable { mutableStateOf(false) }
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localizedConfiguration = remember(baseConfiguration, languageTag) {
        Configuration(baseConfiguration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
    }
    val localizedContext = remember(baseContext, localizedConfiguration) {
        baseContext.createConfigurationContext(localizedConfiguration)
    }
    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
    ) {
        VlogScreenContent(
            state = state,
            languageTag = languageTag,
            googleInformationExpanded = googleInformationExpanded,
            onToggleLanguage = {
                languageTag = if (languageTag == ENGLISH_LANGUAGE_TAG) {
                    CHINESE_LANGUAGE_TAG
                } else {
                    ENGLISH_LANGUAGE_TAG
                }
            },
            onToggleGoogleInformation = {
                googleInformationExpanded = !googleInformationExpanded
            },
            onConfirmDisclosure = onConfirmDisclosure,
            onRequestPicker = onRequestPicker,
            onCreateVlog = onCreateVlog,
            onCancel = onCancel,
            onOpenResult = onOpenResult,
            onAddSubtitles = onAddSubtitles,
            onCancelSubtitles = onCancelSubtitles,
            onOpenSubtitledResult = onOpenSubtitledResult,
            onOpenTranslationInfo = onOpenTranslationInfo,
        )
    }
}

@Composable
private fun VlogScreenContent(
    state: UiState,
    languageTag: String,
    googleInformationExpanded: Boolean,
    onToggleLanguage: () -> Unit,
    onToggleGoogleInformation: () -> Unit,
    onConfirmDisclosure: () -> Unit,
    onRequestPicker: () -> Unit,
    onCreateVlog: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: () -> Unit,
    onAddSubtitles: () -> Unit,
    onCancelSubtitles: () -> Unit,
    onOpenSubtitledResult: () -> Unit,
    onOpenTranslationInfo: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TuiSpacingMd, vertical = TuiSpacingSm),
            verticalArrangement = Arrangement.spacedBy(TuiSpacingSection),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TuiSpacingXs),
            ) {
                TopNavigation(onToggleLanguage = onToggleLanguage)
                FamilyMark(
                    contentDescription = stringResource(R.string.family_mark_content_description),
                )
                Text(
                    text = stringResource(R.string.family_mark_short_label),
                    color = TuiPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            TuiPanel(modifier = Modifier.fillMaxWidth()) {
                PanelContent {
                    SectionHeader(titleRes = R.string.privacy_section_title)
                    Text(
                        text = stringResource(R.string.privacy_disclosure),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.disclosureConfirmed) {
                        DisclosureAcknowledgedStatus()
                    } else {
                        TuiActionButton(
                            onClick = onConfirmDisclosure,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.confirm_disclosure))
                        }
                    }
                }
            }

            TuiPanel(modifier = Modifier.fillMaxWidth()) {
                SplitTaskContent(
                    left = {
                        SectionHeader(
                            index = "01",
                            titleRes = R.string.select_section_title,
                            textStyle = taskTitleTextStyle(),
                        )
                        Text(
                            text = stringResource(
                                R.string.selected_video_count,
                                state.selectedSourceIds.size,
                            ),
                            style = taskContentTextStyle(),
                        )
                        selectSupportingText(state)?.let { reason ->
                            StatusLine(
                                text = reason,
                                textStyle = taskContentTextStyle(),
                            )
                        }
                    },
                    right = {
                        TuiActionButton(
                            onClick = onRequestPicker,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canSelectVideos,
                            variant = TuiActionButtonVariant.Outlined,
                        ) {
                            Text(
                                text = stringResource(R.string.select_videos),
                                style = taskButtonTextStyle(),
                            )
                        }
                    },
                )
            }

            TuiPanel(modifier = Modifier.fillMaxWidth()) {
                SplitTaskContent(
                    left = {
                        SectionHeader(
                            index = "02",
                            titleRes = R.string.create_section_title,
                            textStyle = taskTitleTextStyle(),
                        )
                        OriginalStatusContent(state = state)
                    },
                    right = {
                        OriginalActionContent(
                            state = state,
                            onCreateVlog = onCreateVlog,
                            onCancel = onCancel,
                            onOpenResult = onOpenResult,
                        )
                    },
                )
            }

            TuiPanel(modifier = Modifier.fillMaxWidth()) {
                PanelContent {
                    SectionHeader(
                        index = "03",
                        titleRes = R.string.results_section_title,
                        textStyle = taskTitleTextStyle(),
                    )
                    SubtitleSectionContent(
                        state = state,
                        onAddSubtitles = onAddSubtitles,
                        onCancelSubtitles = onCancelSubtitles,
                        onOpenSubtitledResult = onOpenSubtitledResult,
                        onOpenTranslationInfo = onOpenTranslationInfo,
                        showChineseGoogleExplanation = languageTag == CHINESE_LANGUAGE_TAG,
                        googleInformationExpanded = googleInformationExpanded,
                        onToggleGoogleInformation = onToggleGoogleInformation,
                    )
                }
            }

            Text(
                text = stringResource(R.string.processing_summary),
                modifier = Modifier.fillMaxWidth(),
                color = TuiTextSecondary,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun TopNavigation(onToggleLanguage: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TuiMinimumInteractiveSize),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.terminal_short_label),
            modifier = Modifier.align(Alignment.CenterStart),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            TuiActionButton(
                onClick = onToggleLanguage,
                variant = TuiActionButtonVariant.Outlined,
            ) {
                Text(
                    text = stringResource(R.string.language_switch_target_label),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun DisclosureAcknowledgedStatus() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TuiMinimumInteractiveSize)
            .background(TuiPanelRaised, RoundedCornerShape(TuiPanelCorner))
            .border(TuiBorderWidth, TuiPrimary, RoundedCornerShape(TuiPanelCorner))
            .padding(horizontal = TuiSpacingMd),
        contentAlignment = Alignment.CenterStart,
    ) {
        StatusLine(
            text = stringResource(R.string.disclosure_acknowledged),
            modifier = Modifier.fillMaxWidth(),
            tone = TuiStatusTone.Success,
        )
    }
}

@Composable
private fun SplitTaskContent(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(TuiSpacingSm),
    ) {
        Column(
            modifier = Modifier.weight(2f),
            verticalArrangement = Arrangement.spacedBy(TuiSpacingSm),
        ) {
            left()
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(TuiBorderWidth)
                .background(TuiBorder)
                .clearAndSetSemantics {},
        )
        Column(
            modifier = Modifier.weight(3f),
            verticalArrangement = Arrangement.spacedBy(TuiSpacingSm),
        ) {
            right()
        }
    }
}

@Composable
private fun PanelContent(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TuiSpacingSm)) {
        content()
    }
}

@Composable
private fun SectionHeader(
    @StringRes titleRes: Int,
    index: String? = null,
    textStyle: TextStyle? = null,
) {
    val resolvedTextStyle = textStyle ?: MaterialTheme.typography.titleMedium
    Row(horizontalArrangement = Arrangement.spacedBy(TuiSpacingSm)) {
        index?.let {
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                style = resolvedTextStyle,
            )
        }
        Text(
            text = stringResource(titleRes),
            style = resolvedTextStyle,
        )
    }
}

@Composable
private fun taskTitleTextStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(
    fontSize = TuiTaskTitleFontSize,
    lineHeight = TuiTaskTitleLineHeight,
)

@Composable
private fun taskContentTextStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = TuiTaskContentFontSize,
    lineHeight = TuiTaskContentLineHeight,
)

@Composable
private fun taskButtonTextStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(
    fontSize = TuiTaskContentFontSize,
    lineHeight = TuiTaskContentLineHeight,
)

@Composable
private fun selectSupportingText(state: UiState): String? = when {
    state.canSelectVideos -> null
    !state.disclosureConfirmed -> stringResource(R.string.select_requires_disclosure)
    else -> stringResource(R.string.selection_locked)
}

@Composable
private fun createSupportingText(state: UiState): String? = when {
    state.canCreateVlog -> null
    !state.disclosureConfirmed -> stringResource(R.string.create_requires_disclosure)
    state.setupError != null -> stringResource(state.setupError.stringResource())
    state.selectedSourceIds.isEmpty() -> stringResource(R.string.create_requires_selection)
    else -> null
}

@Composable
private fun OriginalStatusContent(state: UiState) {
    when (val runState = state.runState) {
        RunState.Idle -> StatusLine(
            text = createSupportingText(state) ?: stringResource(R.string.original_ready),
            tone = if (state.canCreateVlog) TuiStatusTone.Success else TuiStatusTone.Neutral,
            textStyle = taskContentTextStyle(),
        )

        is RunState.Active -> {
            val stage = runState.phase.toStagePresentation()
            StageTrace(
                stage.currentStage,
                stage.totalStages,
                stage.labelRes,
                textStyle = taskContentTextStyle(),
            )
            StatusLine(
                text = stringResource(
                    if (state.runCancelRequested) {
                        R.string.cancelling_status
                    } else {
                        R.string.working_status
                    },
                ),
                tone = TuiStatusTone.ActiveWarning,
                decoration = { RotatingGear() },
                textStyle = taskContentTextStyle(),
            )
        }

        is RunState.Failed -> StatusLine(
            text = stringResource(
                state.inputRejection?.stringResource() ?: runState.code.stringResource(),
            ),
            tone = TuiStatusTone.Failure,
            textStyle = taskContentTextStyle(),
        )

        RunState.Cancelled -> StatusLine(
            text = stringResource(R.string.run_cancelled),
            textStyle = taskContentTextStyle(),
        )
        RunState.Succeeded -> StatusLine(
            text = stringResource(R.string.run_succeeded),
            tone = TuiStatusTone.Success,
            textStyle = taskContentTextStyle(),
        )
    }
}

@Composable
private fun OriginalActionContent(
    state: UiState,
    onCreateVlog: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: () -> Unit,
) {
    when (state.runState) {
        RunState.Idle -> TuiActionButton(
            onClick = onCreateVlog,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canCreateVlog,
        ) {
            Text(
                text = stringResource(R.string.create_vlog),
                style = taskButtonTextStyle(),
            )
        }

        is RunState.Active -> TuiActionButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.runCancelRequested,
            variant = TuiActionButtonVariant.Outlined,
            supportingText = if (state.runCancelRequested) {
                stringResource(R.string.cancelling_status)
            } else {
                null
            },
            supportingTextStyle = taskContentTextStyle(),
        ) {
            Text(
                text = stringResource(R.string.cancel_run),
                style = taskButtonTextStyle(),
            )
        }

        RunState.Succeeded -> TuiActionButton(
            onClick = onOpenResult,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.finalUri != null,
            variant = TuiActionButtonVariant.Outlined,
            supportingText = if (state.finalUri == null) {
                stringResource(R.string.result_unavailable)
            } else {
                null
            },
            supportingTextStyle = taskContentTextStyle(),
        ) {
            Text(
                text = stringResource(R.string.open_original_result),
                style = taskButtonTextStyle(),
            )
        }

        RunState.Cancelled,
        is RunState.Failed,
        -> Unit
    }
}

@Composable
private fun SubtitleSectionContent(
    state: UiState,
    onAddSubtitles: () -> Unit,
    onCancelSubtitles: () -> Unit,
    onOpenSubtitledResult: () -> Unit,
    onOpenTranslationInfo: () -> Unit,
    showChineseGoogleExplanation: Boolean,
    googleInformationExpanded: Boolean,
    onToggleGoogleInformation: () -> Unit,
) {
    if (state.runState != RunState.Succeeded) {
        StatusLine(
            text = stringResource(R.string.results_waiting),
            textStyle = taskContentTextStyle(),
        )
        return
    }

    SubtitleContent(
        state = state,
        onAddSubtitles = onAddSubtitles,
        onCancelSubtitles = onCancelSubtitles,
        onOpenSubtitledResult = onOpenSubtitledResult,
    )

    Image(
        painter = painterResource(R.drawable.google_translate_attribution),
        contentDescription = stringResource(R.string.google_translate_attribution),
        modifier = Modifier
            .background(
                TuiGoogleAttributionSurface,
                RoundedCornerShape(TuiPanelCorner),
            )
            .padding(TuiSpacingSm),
    )
    TuiActionButton(
        onClick = onToggleGoogleInformation,
        modifier = Modifier.fillMaxWidth(),
        variant = TuiActionButtonVariant.GoogleOutlined,
    ) {
        Text(
            text = stringResource(
                if (googleInformationExpanded) {
                    R.string.google_translate_service_information_expanded
                } else {
                    R.string.google_translate_service_information_collapsed
                },
            ),
            style = taskButtonTextStyle(),
        )
    }
    if (googleInformationExpanded) {
        if (showChineseGoogleExplanation) {
            Text(
                text = stringResource(R.string.google_translate_chinese_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TuiActionButton(
            onClick = onOpenTranslationInfo,
            modifier = Modifier.fillMaxWidth(),
            variant = TuiActionButtonVariant.GoogleOutlined,
        ) {
            Text(
                text = stringResource(R.string.google_translate_info),
                style = taskButtonTextStyle(),
            )
        }
        Text(
            text = stringResource(R.string.google_translate_disclaimer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SubtitleContent(
    state: UiState,
    onAddSubtitles: () -> Unit,
    onCancelSubtitles: () -> Unit,
    onOpenSubtitledResult: () -> Unit,
) {
    when (val subtitleState = state.subtitleRunState) {
        SubtitleRunState.Idle -> AddSubtitlesButton(state, onAddSubtitles)
        is SubtitleRunState.Active -> {
            val stage = subtitleState.phase.toStagePresentation()
            StageTrace(
                stage.currentStage,
                stage.totalStages,
                stage.labelRes,
                textStyle = taskContentTextStyle(),
            )
            StatusLine(
                text = stringResource(
                    if (state.subtitleCancelRequested) {
                        R.string.cancelling_status
                    } else {
                        R.string.working_status
                    },
                ),
                tone = TuiStatusTone.ActiveWarning,
                decoration = { RotatingGear() },
                textStyle = taskContentTextStyle(),
            )
            TuiActionButton(
                onClick = onCancelSubtitles,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.subtitleCancelRequested,
                variant = TuiActionButtonVariant.Outlined,
                supportingText = if (state.subtitleCancelRequested) {
                    stringResource(R.string.cancelling_status)
                } else {
                    null
                },
                supportingTextStyle = taskContentTextStyle(),
            ) {
                Text(
                    text = stringResource(R.string.cancel_subtitles),
                    style = taskButtonTextStyle(),
                )
            }
        }

        SubtitleRunState.NoSpeech -> {
            StatusLine(
                text = stringResource(R.string.subtitle_no_speech),
                textStyle = taskContentTextStyle(),
            )
            AddSubtitlesButton(state, onAddSubtitles)
        }

        is SubtitleRunState.Failed -> {
            StatusLine(
                text = stringResource(subtitleState.code.stringResource()),
                tone = TuiStatusTone.Failure,
                textStyle = taskContentTextStyle(),
            )
            AddSubtitlesButton(state, onAddSubtitles)
        }

        SubtitleRunState.Cancelled -> {
            StatusLine(
                text = stringResource(R.string.subtitle_cancelled),
                textStyle = taskContentTextStyle(),
            )
            AddSubtitlesButton(state, onAddSubtitles)
        }

        SubtitleRunState.Succeeded -> {
            StatusLine(
                text = stringResource(R.string.subtitle_succeeded),
                tone = TuiStatusTone.Success,
                textStyle = taskContentTextStyle(),
            )
            TuiActionButton(
                onClick = onOpenSubtitledResult,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.subtitledUri != null,
                variant = TuiActionButtonVariant.Outlined,
                supportingText = if (state.subtitledUri == null) {
                    stringResource(R.string.result_unavailable)
                } else {
                    null
                },
                supportingTextStyle = taskContentTextStyle(),
            ) {
                Text(
                    text = stringResource(R.string.open_subtitled_result),
                    style = taskButtonTextStyle(),
                )
            }
        }
    }
}

@Composable
private fun AddSubtitlesButton(state: UiState, onAddSubtitles: () -> Unit) {
    TuiActionButton(
        onClick = onAddSubtitles,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canAddSubtitles,
        variant = TuiActionButtonVariant.GoogleOutlined,
        supportingText = if (state.canAddSubtitles) null else stringResource(R.string.result_unavailable),
        supportingTextStyle = taskContentTextStyle(),
    ) {
        Text(
            text = stringResource(R.string.add_subtitles_with_google),
            style = taskButtonTextStyle(),
        )
    }
}

@StringRes
private fun SubtitleFailureCode.stringResource(): Int = when (this) {
    SubtitleFailureCode.TRANSCRIPTION_FAILED -> R.string.subtitle_failure_transcription
    SubtitleFailureCode.MODEL_DOWNLOAD_FAILED -> R.string.subtitle_failure_model_download
    SubtitleFailureCode.TRANSLATION_FAILED -> R.string.subtitle_failure_translation
    SubtitleFailureCode.SUBTITLE_LAYOUT_FAILED -> R.string.subtitle_failure_layout
    SubtitleFailureCode.PRIVATE_STORAGE_FAILED -> R.string.subtitle_failure_private_storage
    SubtitleFailureCode.RENDER_FAILED -> R.string.subtitle_failure_render
    SubtitleFailureCode.OUTPUT_INSPECTION_FAILED -> R.string.subtitle_failure_output_inspection
    SubtitleFailureCode.PUBLISH_FAILED -> R.string.subtitle_failure_publish
}

@StringRes
private fun SetupError.stringResource(): Int = when (this) {
    SetupError.FIREBASE_NOT_CONFIGURED -> R.string.setup_firebase_not_configured
}

@StringRes
private fun RejectionReason.stringResource(): Int = when (this) {
    RejectionReason.UNREADABLE -> R.string.rejection_unreadable
    RejectionReason.NON_POSITIVE_DURATION -> R.string.rejection_non_positive_duration
    RejectionReason.NO_VIDEO_TRACK -> R.string.rejection_no_video_track
    RejectionReason.VIDEO_DECODE_FAILED -> R.string.rejection_video_decode_failed
}

@StringRes
private fun RunFailureCode.stringResource(): Int = when (this) {
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

private const val ENGLISH_LANGUAGE_TAG = "en"
private const val CHINESE_LANGUAGE_TAG = "zh-CN"
