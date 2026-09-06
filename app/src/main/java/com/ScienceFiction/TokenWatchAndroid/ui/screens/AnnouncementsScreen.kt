package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.accentColor
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import androidx.compose.foundation.shape.CircleShape
import java.time.Instant

/**
 * Inbox for announcements already delivered, opened from the mail glyph in the header.
 *
 * The list shows history, so it keeps items the popup would skip — taken down, outside this build's
 * version range, or dismissed. It also never fetches: that is left to the throttled check on every
 * foreground transition. Fetching here would let an announcement just read pop straight back up.
 */
@Composable
internal fun AnnouncementsScreen(
    announcements: List<Announcement>,
    unread: Set<String>,
    fetchFailed: Boolean,
    loc: L10n,
    onOpen: (Announcement) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            modifier = Modifier.statusBarsPadding(),
            title = "ANNOUNCEMENTS",
            leading = {
                TerminalTextButton(
                    text = "[back]",
                    color = Term.Green,
                    onClick = onBack,
                    accessibilityLabel = loc.a11yBack,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            TerminalBox {
                if (announcements.isEmpty()) {
                    // A cache lets the list render offline, so "failed" only means nothing ever arrived.
                    Text(
                        text = if (fetchFailed) loc.announcementsUnavailable else loc.announcementsEmpty,
                        color = Term.Dim,
                        style = terminalTextStyle(12.sp),
                    )
                } else {
                    announcements.forEachIndexed { index, announcement ->
                        if (index > 0) TerminalDivider()
                        AnnouncementRow(
                            announcement = announcement,
                            unread = announcement.id in unread,
                            loc = loc,
                            now = now,
                            onClick = { onOpen(announcement) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementRow(
    announcement: Announcement,
    unread: Boolean,
    loc: L10n,
    now: Instant,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val korean = loc.lang == Lang.KO
    val title = announcement.title.resolved(korean)
        .ifEmpty { announcement.body.resolved(korean).lineSequence().firstOrNull().orEmpty() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = announcement.kind.chromeLabel,
                color = announcement.kind.accentColor(),
                style = terminalTextStyle(12.sp, FontWeight.SemiBold),
            )
            Text(
                text = loc.announcementDate(Instant.ofEpochMilli(announcement.publishedAt), now),
                color = Term.Dim,
                style = terminalTextStyle(11.sp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (unread) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Term.Green)
                        .clearAndSetSemantics { },
                )
            }
            Text(
                text = title,
                color = Term.Foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = terminalTextStyle(
                    14.sp,
                    if (unread) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }
}
