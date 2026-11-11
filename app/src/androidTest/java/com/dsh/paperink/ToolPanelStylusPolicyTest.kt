package com.dsh.paperink

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.5.4：画笔面板的「点空白收起」规则，和「更多」「单双页」等二级菜单对齐。
 *
 * 契约：面板开着 + 落点在面板外 → 收起（并消费这次按下）；
 * 落在面板内必须放行，否则选笔型的 DOWN-UP 会被拆散（v0.4.5 的 bug）。
 * 手指和手写笔一视同仁。
 */
@RunWith(AndroidJUnit4::class)
class ToolPanelStylusPolicyTest {

    @Test
    fun downInsidePanel_keepsPanelOpen() {
        assertFalse(shouldDismissInkPanelOnDown(true, true))
    }

    @Test
    fun downOutsidePanel_dismisses() {
        assertTrue(shouldDismissInkPanelOnDown(true, false))
    }

    @Test
    fun downWhenPanelClosed_noop() {
        assertFalse(shouldDismissInkPanelOnDown(false, false))
    }

    @Test
    fun downWhenPanelClosedInside_noop() {
        assertFalse(shouldDismissInkPanelOnDown(false, true))
    }

    @Test
    fun downOutsidePanelWithUnknownToolType_dismisses() {
        // 手指/笔尾/鼠标等任何能产生 ACTION_DOWN 的工具，点空白都该收起面板。
        assertTrue(shouldDismissInkPanelOnDown(true, false))
    }
}
