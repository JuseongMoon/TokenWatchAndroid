package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryA11y
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryComingSoon
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryFailed
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryHint
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryLoading
import com.ScienceFiction.TokenWatchAndroid.localization.bestiarySummary
import com.ScienceFiction.TokenWatchAndroid.localization.bestiaryUnknown
import com.ScienceFiction.TokenWatchAndroid.localization.monsterName
import com.ScienceFiction.TokenWatchAndroid.monster.BestiaryRow
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterSpriteSet
import com.ScienceFiction.TokenWatchAndroid.monster.MonsterStore
import com.ScienceFiction.TokenWatchAndroid.ui.components.AnimatedPixelSpriteView
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSprite
import com.ScienceFiction.TokenWatchAndroid.ui.components.PixelSpriteView
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalButton
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalSpinner
import com.ScienceFiction.TokenWatchAndroid.ui.components.toPixelSprite
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import kotlinx.coroutines.launch

/**
 * The account screen's monster bestiary, mirroring iOS `BestiaryBox`: four columns, as wide as the avatar grid
 * below. Each species shows how many this account has defeated, and a species never defeated is a silhouette
 * with only a question mark (its name hidden too). Places for monsters still to come fill gray squares with a
 * question mark up to at least eight cells (4 × 2). The defeats are read from the server again every time it
 * opens; demo mode shows the sample adventurer's.
 */
@Composable
internal fun BestiaryBox(monsters: MonsterStore, sprites: MonsterSpriteSet, loc: L10n) {
    val state by monsters.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rows = remember(state.bestiaryKills, state.config, state.isDemo) { monsters.bestiaryRows(state) }
    LaunchedEffect(Unit) { monsters.refreshBestiary() }

    TerminalBox(title = "BESTIARY") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                rows != null -> {
                    Text(
                        text = loc.bestiarySummary(
                            found = rows.count { it.discovered },
                            total = rows.size,
                            kills = rows.sumOf { it.kills },
                        ),
                        color = Term.Foreground,
                        style = terminalTextStyle(11.sp, FontWeight.SemiBold),
                    )
                    // A plain column of rows rather than a lazy grid, like the avatar picker: a handful of
                    // sprites inside a scrolling screen. Null is a place for a monster still to come.
                    val slots: List<BestiaryRow?> = rows + List(maxOf(0, MINIMUM_SLOTS - rows.size)) { null }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        slots.chunked(COLUMNS).forEach { line ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                line.forEach { row ->
                                    if (row != null) {
                                        BestiaryCell(row, sprites, loc, Modifier.weight(1f))
                                    } else {
                                        ComingSoonCell(loc, Modifier.weight(1f))
                                    }
                                }
                                repeat(COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Text(text = loc.bestiaryHint, color = Term.Dim, style = terminalTextStyle(10.sp))
                }
                state.bestiaryFailed -> {
                    Text(text = loc.bestiaryFailed, color = Term.Red, style = terminalTextStyle(11.sp))
                    TerminalButton(title = "[ retry ]", onClick = { scope.launch { monsters.refreshBestiary() } })
                }
                else -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TerminalSpinner(color = Term.Cyan, size = 12.sp)
                    Text(text = loc.bestiaryLoading, color = Term.Dim, style = terminalTextStyle(11.sp))
                }
            }
        }
    }
}

private const val COLUMNS = 4

/** The fewest cells the bestiary shows. New monsters take the empty places in turn. */
private const val MINIMUM_SLOTS = 8

@Composable
private fun BestiaryCell(row: BestiaryRow, sprites: MonsterSpriteSet, loc: L10n, modifier: Modifier = Modifier) {
    // Not on a gauge: only the blue slime itself is sky blue here.
    val sprite = remember(row.entry, sprites) {
        sprites.body(row.id, row.entry)?.toPixelSprite(gray = false)
            ?: if (MonsterSpriteSet.skySlime(row.id, sessionWindow = false)) PixelSprite.SlimeSky else PixelSprite.Slime
    }
    val name = loc.monsterName(row.id)
    val description = if (row.discovered) loc.bestiaryA11y(name, row.kills) else loc.bestiaryUnknown
    Column(
        modifier = modifier
            .border(width = 1.dp, color = Term.Dim.copy(alpha = 0.3f))
            .padding(vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.BottomCenter) {
            if (row.discovered) {
                AnimatedPixelSpriteView(sprite = sprite, cell = 4.dp, tickMillis = 500L)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    PixelSpriteView(sprite = sprite, frameIndex = 0, cell = 4.dp, flatColor = Term.Dim.copy(alpha = 0.45f))
                    Text(text = "?", color = Term.Foreground, style = terminalTextStyle(15.sp, FontWeight.Bold))
                }
            }
        }
        Text(
            text = if (row.discovered) name else "???",
            color = if (row.discovered) Term.Foreground else Term.Dim,
            style = terminalTextStyle(11.sp, FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = if (row.discovered) "×${row.kills}" else "—",
            color = if (row.discovered) Term.Green else Term.Dim,
            style = terminalTextStyle(11.sp),
        )
    }
}

/** A place for a monster still to come: a gray square the size of a silhouette, with a question mark. */
@Composable
private fun ComingSoonCell(loc: L10n, modifier: Modifier = Modifier) {
    val description = loc.bestiaryComingSoon
    Column(
        modifier = modifier
            .border(width = 1.dp, color = Term.Dim.copy(alpha = 0.3f))
            .padding(vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Term.Dim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "?", color = Term.Foreground, style = terminalTextStyle(15.sp, FontWeight.Bold))
            }
        }
        Text(
            text = "???",
            color = Term.Dim,
            style = terminalTextStyle(11.sp, FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(text = "—", color = Term.Dim, style = terminalTextStyle(11.sp))
    }
}
