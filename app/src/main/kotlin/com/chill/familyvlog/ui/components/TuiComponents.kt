package com.chill.familyvlog.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.chill.familyvlog.R
import com.chill.familyvlog.ui.theme.TuiActiveWarning
import com.chill.familyvlog.ui.theme.TuiBackground
import com.chill.familyvlog.ui.theme.TuiBorder
import com.chill.familyvlog.ui.theme.TuiBorderWidth
import com.chill.familyvlog.ui.theme.TuiButtonCorner
import com.chill.familyvlog.ui.theme.TuiDisabledContainer
import com.chill.familyvlog.ui.theme.TuiDisabledContent
import com.chill.familyvlog.ui.theme.TuiFailure
import com.chill.familyvlog.ui.theme.TuiGoogle
import com.chill.familyvlog.ui.theme.TuiGearRotationDurationMillis
import com.chill.familyvlog.ui.theme.TuiMinimumInteractiveSize
import com.chill.familyvlog.ui.theme.TuiPanel
import com.chill.familyvlog.ui.theme.TuiPanelCorner
import com.chill.familyvlog.ui.theme.TuiPressedTranslationY
import com.chill.familyvlog.ui.theme.TuiPrimary
import com.chill.familyvlog.ui.theme.TuiSpacingSm
import com.chill.familyvlog.ui.theme.TuiSpacingMd
import com.chill.familyvlog.ui.theme.TuiTextPrimary
import com.chill.familyvlog.ui.theme.TuiTextSecondary

@Composable
fun TuiPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = TuiBorder,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(TuiPanel, RoundedCornerShape(TuiPanelCorner))
            .border(TuiBorderWidth, borderColor, RoundedCornerShape(TuiPanelCorner))
            .padding(TuiSpacingMd),
        content = content,
    )
}

enum class TuiActionButtonVariant {
    Filled,
    Outlined,
    GoogleOutlined,
}

internal val TuiActionButtonVariantKey =
    SemanticsPropertyKey<TuiActionButtonVariant>("TuiActionButtonVariant")
internal var SemanticsPropertyReceiver.tuiActionButtonVariant by TuiActionButtonVariantKey

internal fun filledButtonBorder(enabled: Boolean, pressed: Boolean): BorderStroke? = when {
    !enabled -> BorderStroke(TuiBorderWidth, TuiDisabledContent)
    pressed -> BorderStroke(TuiBorderWidth, TuiPrimary)
    else -> null
}

@Composable
fun TuiActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: TuiActionButtonVariant = TuiActionButtonVariant.Filled,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    supportingText: String? = null,
    supportingTextStyle: TextStyle? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val buttonModifier = Modifier
        .sizeIn(
            minWidth = TuiMinimumInteractiveSize,
            minHeight = TuiMinimumInteractiveSize,
        )
        .graphicsLayer {
            translationY = if (pressed) TuiPressedTranslationY.toPx() else 0f
        }
        .semantics { tuiActionButtonVariant = variant }
        .then(modifier)
    val shape = RoundedCornerShape(TuiButtonCorner)
    val accent = if (variant == TuiActionButtonVariant.GoogleOutlined) TuiGoogle else TuiPrimary
    val containerColor = when {
        !enabled -> TuiDisabledContainer
        variant == TuiActionButtonVariant.Filled && !pressed -> TuiPrimary
        variant != TuiActionButtonVariant.Filled && pressed -> accent
        else -> TuiPanel
    }
    val contentColor = when {
        !enabled -> TuiDisabledContent
        variant == TuiActionButtonVariant.Filled && !pressed -> TuiBackground
        variant != TuiActionButtonVariant.Filled && pressed -> TuiBackground
        else -> accent
    }
    val colors = ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = TuiDisabledContainer,
        disabledContentColor = TuiDisabledContent,
    )
    val borderColor = if (enabled) accent else TuiDisabledContent

    Column {
        if (variant == TuiActionButtonVariant.Filled) {
            Button(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled,
                shape = shape,
                colors = colors,
                border = filledButtonBorder(enabled = enabled, pressed = pressed),
                interactionSource = interactionSource,
                content = content,
            )
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                    disabledContainerColor = TuiDisabledContainer,
                    disabledContentColor = TuiDisabledContent,
                ),
                border = BorderStroke(TuiBorderWidth, borderColor),
                interactionSource = interactionSource,
                content = content,
            )
        }
        supportingText?.let { text ->
            Text(
                text = text,
                modifier = Modifier.padding(top = TuiSpacingSm),
                color = if (enabled) TuiTextSecondary else TuiDisabledContent,
                style = supportingTextStyle ?: MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun StageTrace(
    currentStage: Int,
    totalStages: Int,
    @StringRes stageLabelRes: Int,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
) {
    val label = stringResource(stageLabelRes)
    val fullText = stringResource(R.string.stage_trace_format, currentStage, totalStages, label)
    val prefixLength = fullText.indexOf(label).coerceAtLeast(0)
    val styled = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            append(fullText.substring(0, prefixLength))
        }
        append(fullText.substring(prefixLength))
    }
    Text(
        text = styled,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = TuiTextPrimary,
        style = textStyle ?: MaterialTheme.typography.bodyMedium,
    )
}

enum class TuiStatusTone {
    Neutral,
    ActiveWarning,
    Failure,
    Success,
}

internal val TuiStatusToneKey = SemanticsPropertyKey<TuiStatusTone>("TuiStatusTone")
internal var SemanticsPropertyReceiver.tuiStatusTone by TuiStatusToneKey
internal val TuiStatusHasDecorationKey = SemanticsPropertyKey<Boolean>("TuiStatusHasDecoration")
internal var SemanticsPropertyReceiver.tuiStatusHasDecoration by TuiStatusHasDecorationKey

@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    tone: TuiStatusTone = TuiStatusTone.Neutral,
    decoration: (@Composable (() -> Unit))? = null,
    textStyle: TextStyle? = null,
) {
    val color = when (tone) {
        TuiStatusTone.Neutral -> TuiTextSecondary
        TuiStatusTone.ActiveWarning -> TuiActiveWarning
        TuiStatusTone.Failure -> TuiFailure
        TuiStatusTone.Success -> TuiPrimary
    }
    Row(
        modifier = modifier.semantics {
            tuiStatusTone = tone
            tuiStatusHasDecoration = decoration != null
        },
    ) {
        decoration?.invoke()
        if (decoration != null) Spacer(modifier = Modifier.width(TuiSpacingSm))
        Text(
            text = text,
            color = color,
            style = textStyle ?: MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun RotatingGear(
    modifier: Modifier = Modifier,
    color: Color = TuiActiveWarning,
) {
    val transition = rememberInfiniteTransition(label = "tui-gear")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(TuiGearRotationDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tui-gear-rotation",
    )
    Text(
        text = "\u2699\uFE0E",
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .clearAndSetSemantics {},
    )
}
