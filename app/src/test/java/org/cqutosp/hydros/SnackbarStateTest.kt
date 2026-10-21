package org.cqutosp.hydros

import org.cqutosp.hydros.ui.components.*
import org.junit.Assert.*
import org.junit.Test

class SnackbarStateTest {
    @Test fun latestMessageReplacesRatherThanQueuesAndRepeatedTextIsANewEvent() {
        val state = HydrosSnackbarState()
        state.show("first")
        val first = state.notice.value!!.id
        state.unlock(first)
        state.show("first")
        assertTrue(state.notice.value!!.id > first)
        assertFalse(state.notice.value!!.canDismissOutside)
        state.show("latest", error = true)
        assertEquals("latest", state.notice.value!!.text)
        assertTrue(state.notice.value!!.error)
        state.dismiss(state.notice.value!!.id)
        assertNull(state.notice.value)
    }
    @Test fun outsideIsLockedAndStaleTimerCannotDismissOrUnlockReplacement() {
        val state = HydrosSnackbarState()
        state.show("first")
        val old = state.notice.value!!.id
        state.dismissOutside()
        assertNotNull(state.notice.value)
        state.show("second")
        state.unlock(old)
        state.dismiss(old)
        assertFalse(state.notice.value!!.canDismissOutside)
        state.unlock(state.notice.value!!.id)
        state.dismissOutside()
        assertNull(state.notice.value)
    }
    @Test fun actionRunsOnceAndCanCreateANewNotification() {
        val state = HydrosSnackbarState()
        var actions = 0
        state.show("retry", SnackbarAction("retry") { actions++; state.show("new") })
        val id = state.notice.value!!.id
        state.performAction(id)
        state.performAction(id)
        assertEquals(1, actions)
        assertEquals("new", state.notice.value!!.text)
    }
    @Test fun staleActionDoesNotRunAndModalsRestorePreviousHost() {
        val state = HydrosSnackbarState()
        state.show("old", SnackbarAction("action") { fail("Stale action") })
        val old = state.notice.value!!.id
        state.show("new")
        state.performAction(old)
        val sheet = Any(); val dialog = Any()
        state.registerModal(sheet); state.registerModal(dialog)
        assertSame(dialog, state.modalOwners.value.last())
        state.unregisterModal(dialog)
        assertSame(sheet, state.modalOwners.value.last())
        state.unregisterModal(sheet)
        assertTrue(state.modalOwners.value.isEmpty())
    }
    @Test fun backgroundCannotReplaceUserOrDeviceAndExplicitKeysDeduplicate() {
        val state = HydrosSnackbarState()
        state.show("操作失败", source = "device", dedupKey = "close:1")
        val first = state.notice.value!!.id
        assertNull(state.show("后台刷新失败", source = "background"))
        state.show("操作失败", source = "device", dedupKey = "close:1")
        assertEquals(first, state.notice.value!!.id)
        state.show("切换成功", severity = org.cqutosp.hydros.data.feedback.FeedbackSeverity.SUCCESS)
        assertFalse(state.notice.value!!.error)
        state.show("请检查水流", severity = org.cqutosp.hydros.data.feedback.FeedbackSeverity.WARNING)
        assertFalse(state.notice.value!!.error)
    }

}
