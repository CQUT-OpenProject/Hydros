package org.cqutosp.hydros

import org.cqutosp.hydros.data.repository.DEVICE_BROADCAST_PREFIX
import org.cqutosp.hydros.ui.scanner.*
import org.junit.Assert.*
import org.junit.Test

class ScanSessionTest {
    @Test fun failedQueryCanRetryWithoutScanningAndRejectsLateResults() {
        val session = ScanSession()
        session.codes(0, listOf("$DEVICE_BROADCAST_PREFIX,M,AABBCCDDEEFF"))
        session.resolved(0, null)
        session.retryQuery()
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
        assertEquals("AABBCCDDEEFF", session.state.value.mac)
        session.resolved(0, "late")
        assertNull(session.state.value.deviceId)
        session.resolved(1, "device")
        assertEquals("device", session.state.value.deviceId)
    }
    private val first = "$DEVICE_BROADCAST_PREFIX,M,AA:BB:CC:DD:EE:FF"
    private val second = "$DEVICE_BROADCAST_PREFIX,M,112233445566"
    @Test fun validDeviceIsFoundAfterUnrelatedQrAndDuplicateMacsCollapse() {
        val session = ScanSession()
        session.codes(0, listOf("https://example.com", first, "$DEVICE_BROADCAST_PREFIX,M,AABBCCDDEEFF"))
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
        assertEquals("AABBCCDDEEFF", session.state.value.mac)
    }
    @Test fun multipleDevicesRequireSelection() {
        val session = ScanSession()
        session.codes(0, listOf(first, second, first))
        assertEquals(2, session.state.value.candidates.size)
        assertEquals(ScanPhase.CHOOSING, session.state.value.phase)
        session.choose("missing")
        assertEquals(ScanPhase.CHOOSING, session.state.value.phase)
        session.choose("112233445566")
        assertEquals("112233445566", session.state.value.mac)
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
    }
    @Test fun failureRequiresRetryAndSameCodeWorksAgain() {
        val session = ScanSession()
        session.codes(0, listOf(first))
        session.resolved(0, null)
        session.codes(0, listOf(first))
        assertEquals(ScanPhase.ERROR, session.state.value.phase)
        session.retry()
        session.codes(1, listOf(first))
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
    }
    @Test fun concurrentAndLateResultsCannotReplaceQueryOrNavigation() {
        val session = ScanSession()
        session.codes(0, listOf(first))
        session.codes(0, listOf(second))
        assertEquals("AABBCCDDEEFF", session.state.value.mac)
        session.resolved(0, "device")
        session.resolved(0, "other")
        assertEquals("device", session.state.value.deviceId)
        session.retry()
        session.resolved(0, "late")
        session.fail(0, "late failure")
        session.codes(0, listOf(second))
        assertEquals(ScanState(generation = 1), session.state.value)
    }
    @Test fun photoPickerPausesCameraAndCancellationResumesScanning() {
        val session = ScanSession()
        session.pick()
        session.codes(0, listOf(first))
        session.pauseCamera()
        assertEquals(ScanPhase.PICKING, session.state.value.phase)
        assertNull(session.picked(false))
        assertEquals(ScanPhase.SCANNING, session.state.value.phase)
        session.pick()
        val token = session.picked(true)!!
        session.codes(token, listOf(first)) // Live frames cannot masquerade as photo results.
        assertEquals(ScanPhase.DECODING, session.state.value.phase)
        session.codes(token, listOf(first), photo = true)
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
    }
    @Test fun emptyAndUnsupportedPhotosHaveDistinctErrorsAndCanBeRetried() {
        val session = ScanSession()
        session.pick()
        session.codes(session.picked(true)!!, emptyList(), photo = true)
        assertEquals("图片中未识别到二维码", session.state.value.message)
        session.pick()
        session.codes(session.picked(true)!!, listOf("ordinary qr"), photo = true)
        assertEquals("不是支持的用水设备二维码", session.state.value.message)
    }
    @Test fun backgroundInvalidatesLiveCallbacksButKeepsPendingQuery() {
        val session = ScanSession()
        session.pauseCamera()
        session.codes(0, listOf(first))
        assertEquals(ScanPhase.SCANNING, session.state.value.phase)
        session.codes(1, listOf(first))
        session.pauseCamera()
        session.resolved(1, "device")
        assertEquals(ScanPhase.DONE, session.state.value.phase)
    }
}
