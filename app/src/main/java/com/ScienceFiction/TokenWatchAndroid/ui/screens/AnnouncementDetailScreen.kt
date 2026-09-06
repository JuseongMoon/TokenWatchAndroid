package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ScienceFiction.TokenWatchAndroid.domain.Announcement
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.Lang
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.time.Instant

/** One announcement in full, reached from the inbox. Same typesetting as the startup popup. */
@Composable
internal fun AnnouncementDetailScreen(
    announcement: Announcement,
    loc: L10n,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    val korean = loc.lang == Lang.KO
    val title = announcement.title.resolved(korean)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Term.Background),
    ) {
        ScreenTopBar(
            modifier = Modifier.statusBarsPadding(),
            title = announcement.kind.chromeLabel,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = loc.announcementDate(Instant.ofEpochMilli(announcement.publishedAt), now),
                color = Term.Dim,
                style = terminalTextStyle(11.sp),
            )
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    color = Term.Foreground,
                    style = terminalTextStyle(15.sp, FontWeight.Bold),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Term.Dim.copy(alpha = 0.35f))
                    .padding(top = 1.dp),
            )
            SelectionContainer {
                Text(
                    text = announcement.body.resolved(korean),
                    color = Term.Foreground.copy(alpha = 0.92f),
                    style = terminalTextStyle(13.sp),
                )
            }
        }
    }
}
