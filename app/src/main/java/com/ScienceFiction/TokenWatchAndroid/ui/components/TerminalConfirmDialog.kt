package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

/** Full-screen terminal confirmation modal shared by destructive actions. */
@Composable
fun TerminalConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    accountLabel: String? = null,
    titleColor: Color = Term.Red,
    confirmColor: Color = Term.Red,
    borderColor: Color = confirmColor,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .background(Term.Background)
                    .border(1.5.dp, borderColor.copy(alpha = 0.7f))
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    style = terminalDialogText(15, FontWeight.Bold),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Term.Dim.copy(alpha = 0.35f))
                        .padding(top = 1.dp),
                )
                accountLabel?.takeIf(String::isNotBlank)?.let { label ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "▸", color = Term.Dim, style = terminalDialogText(13))
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                color = Term.Cyan,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                style = terminalDialogText(13, FontWeight.SemiBold),
                            )
                        }
                    }
                }
                Text(
                    text = message,
                    color = Term.Dim,
                    style = terminalDialogText(12),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TerminalButton(
                        title = cancelLabel,
                        color = Term.Foreground,
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    )
                    TerminalButton(
                        title = confirmLabel,
                        color = confirmColor,
                        modifier = Modifier.weight(1f),
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

private fun terminalDialogText(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    fontWeight = weight,
)
