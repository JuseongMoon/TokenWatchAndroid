package com.ScienceFiction.TokenWatchAndroid.ui.components

import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** A credit gauge's card row: the same nine cells right of the bar as a subscription gauge, the amount below it. */
class UsageBarTextTest {
    @Test fun percentCellsAreNineCellsForUsedAndLeft() {
        assertEquals("  3% used", gaugePercentCell(3.4, "used"))
        assertEquals(" 42% left", gaugePercentCell(41.6, "left"))
        assertEquals("100% left", gaugePercentCell(100.0, "left"))
        assertEquals(gaugePercentCell(7.0, "used").length, gaugePercentCell(7.0, "left").length)
        assertEquals(9, gaugePercentCell(0.0, "left").length)
    }

    @Test fun creditAmountMarksAnEstimatedTotal() {
        fun credit(estimated: Boolean, text: String?) = UsageWindow(
            "Credits", 100.0, null, WindowKind.WEEKLY, style = UsageStyle.CREDIT_GAUGE,
            valueText = text, estimatedTotal = estimated,
        )
        assertEquals("0.00 credits left", creditBalanceText(credit(false, "0.00 credits left")))
        assertEquals("~94.20 CNY", creditBalanceText(credit(true, "94.20 CNY")))
        assertEquals("—", creditBalanceText(credit(false, null)))
    }
}
