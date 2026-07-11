package com.ScienceFiction.TokenWatchAndroid

import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalThemeTest {
    @Test
    fun statusColorMatchesIosThresholds() {
        assertEquals(Term.Red, Term.statusColor(9.99))
        assertEquals(Term.Yellow, Term.statusColor(10.0))
        assertEquals(Term.Yellow, Term.statusColor(24.99))
        assertEquals(Term.Green, Term.statusColor(25.0))
    }
}
