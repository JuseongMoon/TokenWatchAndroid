package com.ScienceFiction.TokenWatchAndroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.time.Instant

/**
 * Startup announcement / patch-note popup: a ruled card over a dim scrim, in the same key as
 * [TerminalConfirmDialog].
 *
 * Height: a short announcement takes only the room it needs, a long one grows to half the screen
 * and scrolls its body inside that. Tapping the scrim is the same as [ 닫기 ] — hidden for this
 * launch — while [ 다시 열지 않기 ] excludes it permanently. Both decisions belong to the store.
 */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    loc: L10n,
    onClose: () -> Unit,
    onDismissForever: () -> Unit,
    now: Instant = Instant.now(),
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    val accent = announcement.kind.accentColor()
    val korean = loc.lang == Lang.KO
    val title = announcement.title.resolved(korean)
    val body = announcement.body.resolved(korean)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onClose,
                )
                .padding(horizontal = 28.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardMaxHeight = maxHeight * 0.5f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .heightIn(max = cardMaxHeight)
                    .background(Term.Background)
                    .border(1.5.dp, accent.copy(alpha = 0.7f))
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = announcement.kind.chromeLabel,
                        color = accent,
                        style = announcementText(12, FontWeight.SemiBold),
                    )
                    Text(
                        text = loc.announcementDate(Instant.ofEpochMilli(announcement.publishedAt), now),
                        color = Term.Dim,
                        style = announcementText(11),
                    )
                }

                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        color = Term.Foreground,
                        style = announcementText(15, FontWeight.Bold),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Term.Dim.copy(alpha = 0.35f))
                        .padding(top = 1.dp),
                )

                // Takes only what it needs when short, stops at the card's cap and scrolls when long.
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    SelectionContainer(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = body,
                            color = Term.Foreground.copy(alpha = 0.92f),
                            style = announcementText(13),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Uneven split: "Don't show again" is the far longer label and would wrap first.
                    TerminalButton(
                        title = "[ ${loc.announcementClose} ]",
                        color = Term.Foreground,
                        modifier = Modifier.weight(1f),
                        onClick = onClose,
                    )
                    TerminalButton(
                        title = "[ ${loc.announcementNever} ]",
                        color = accent,
                        modifier = Modifier.weight(2f),
                        onClick = onDismissForever,
                    )
                }
            }
        }
    }
}

/** Accent shared by the popup border/header and the inbox row tag. */
internal fun Announcement.Kind.accentColor(): Color = when (this) {
    Announcement.Kind.NOTICE -> Term.Cyan
    Announcement.Kind.PATCH -> Term.Green
}

private fun announcementText(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    fontWeight = weight,
)
