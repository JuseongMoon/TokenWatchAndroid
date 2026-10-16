package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.R
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarPreset
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

/*
 * Shared app account pieces: the brand sign-in buttons, the avatar picker, the nickname field and the
 * one-line notice. The sign-in buttons are the one exception to the terminal theme. They match iOS
 * exactly — Apple's system button in its white style beside a Google button of the same shape: white
 * fill, black logo and label, system sans-serif rather than the terminal monospace.
 */

private val BrandButtonHeight = 50.dp
private val BrandButtonShape = RoundedCornerShape(12.dp)

/**
 * The two sign-in buttons, Apple first. [continueLabels] switches both to "Continue with …" for
 * linking. A hidden Google button means Google cannot be used in this build. Labels stay English.
 */
@Composable
fun AccountBrandButtons(
    showApple: Boolean,
    showGoogle: Boolean,
    enabled: Boolean,
    onApple: () -> Unit,
    onGoogle: () -> Unit,
    modifier: Modifier = Modifier,
    continueLabels: Boolean = false,
) {
    val verb = if (continueLabels) "Continue with" else "Sign in with"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showApple) {
            BrandButton(
                icon = R.drawable.ic_apple_logo,
                tintIcon = true,
                label = "$verb Apple",
                enabled = enabled,
                onClick = onApple,
            )
        }
        if (showGoogle) {
            BrandButton(
                icon = R.drawable.ic_google_logo,
                tintIcon = false,
                label = "$verb Google",
                enabled = enabled,
                onClick = onGoogle,
            )
        }
    }
}

@Composable
private fun BrandButton(icon: Int, tintIcon: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BrandButtonHeight)
            // Dims while pressed, like the system Apple button.
            .alpha(if (pressed) 0.75f else 1f)
            .clip(BrandButtonShape)
            .background(Color.White)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = if (tintIcon) ColorFilter.tint(Color.Black) else null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = Color.Black,
                fontFamily = FontFamily.Default,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            // iOS lets the label shrink to 80% on narrow screens.
            autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = 19.sp),
        )
    }
}

/** One avatar, still frame only. [cell] is one pixel in dp: 4 (64 dp) / 3 (48 dp) / 2 (32 dp). */
@Composable
fun AvatarView(
    preset: AvatarPreset,
    cell: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val sprite = remember(preset) { preset.toPixelSprite() }
    PixelSpriteView(
        sprite = sprite,
        frameIndex = 0,
        cell = cell,
        modifier = modifier.then(
            if (contentDescription != null) {
                Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        ),
    )
}

internal fun AvatarPreset.toPixelSprite(): PixelSprite = PixelSprite(
    frames = listOf(bitmap),
    palette = palette.mapValues { (_, rgb) -> Color(0xFF000000 or rgb.toLong()) },
)

/** The avatar picker: four columns, 64 dp avatars (4 dp per pixel), green border and `>` on the selection. */
@Composable
fun AvatarPicker(
    catalog: AvatarCatalog,
    selection: String,
    korean: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A plain column of rows rather than a lazy grid: 16 still images inside a scrolling screen.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.all.chunked(COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { avatar ->
                    AvatarCell(
                        avatar = avatar,
                        selected = avatar.id == selection,
                        label = avatar.name(korean),
                        enabled = enabled,
                        onClick = { onSelect(avatar.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val COLUMNS = 4

@Composable
private fun AvatarCell(
    avatar: AvatarPreset,
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Term.Green else Term.Dim.copy(alpha = 0.5f),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        AvatarView(preset = avatar, cell = 4.dp, modifier = Modifier.padding(5.dp))
        if (selected) {
            // Sits on the cell's top-left corner, outside the avatar padding, as on iOS.
            Text(
                text = ">",
                color = Term.Green,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp),
            )
        }
    }
}

/**
 * Terminal-style nickname input. Validation happens on save, never while typing, so Hangul still
 * being composed by the IME is not checked half-finished.
 */
@Composable
fun NicknameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Term.Foreground)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle.copy(color = if (enabled) Term.Foreground else Term.Dim),
        cursorBrush = SolidColor(Term.Green),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Term.Dim.copy(alpha = 0.5f)),
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(12.dp)) {
                if (value.isEmpty()) Text(text = placeholder, color = Term.Dim, style = textStyle)
                inner()
            }
        },
    )
}

/** One-line notice: red for failures, green for completions. */
@Composable
fun AccountNoticeLine(text: String, modifier: Modifier = Modifier, color: Color = Term.Red) {
    Text(
        text = text,
        color = color,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        modifier = modifier.fillMaxWidth(),
    )
}
