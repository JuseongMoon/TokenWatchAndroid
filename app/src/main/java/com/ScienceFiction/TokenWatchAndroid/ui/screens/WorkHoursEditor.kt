package com.ScienceFiction.TokenWatchAndroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ScienceFiction.TokenWatchAndroid.domain.WorkHoursSchedule
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.ui.components.TerminalBox
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term

@Composable
internal fun WorkHoursEditor(
    initial: WorkHoursSchedule,
    loc: L10n,
    onSave: (WorkHoursSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(20.dp).background(Term.Background)) {
            TerminalBox(title = "WORK HOURS") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(loc.workHoursEditorHelp, color = Term.Dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(34.dp))
                        repeat(7) { day ->
                            Text(loc.weekdayShort(day), color = Term.Cyan, fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                        repeat(24) { hour ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(hour.toString().padStart(2, '0'), color = Term.Dim,
                                    fontFamily = FontFamily.Monospace, fontSize = 9.sp, modifier = Modifier.width(34.dp))
                                repeat(7) { day ->
                                    val on = draft.isOn(day, hour)
                                    Box(
                                        Modifier.weight(1f).height(18.dp).padding(1.dp)
                                            .background(if (on) Term.Green else Term.Track)
                                            .border(0.5.dp, Term.Dim.copy(alpha = 0.4f))
                                            .clickable { draft = draft.setting(day, hour, !on) },
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TerminalTextButton(loc.workHoursClear, onClick = { draft = WorkHoursSchedule() }, color = Term.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TerminalTextButton(loc.workHoursCancel, onClick = onDismiss, color = Term.Dim)
                            TerminalTextButton(loc.workHoursSave, onClick = { onSave(draft) }, color = Term.Green)
                        }
                    }
                }
            }
        }
    }
}
