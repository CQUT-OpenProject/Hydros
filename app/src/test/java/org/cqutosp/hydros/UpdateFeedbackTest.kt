package org.cqutosp.hydros

import android.app.DownloadManager
import org.cqutosp.hydros.data.update.*
import org.cqutosp.hydros.data.feedback.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateFeedbackTest {
    @Test fun downloadReasonsHaveDifferentRecoveryAdvice() {
        assertTrue(downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE).contains("释放空间"))
        assertTrue(downloadFailureMessage(DownloadManager.ERROR_FILE_ERROR).contains("保存"))
        assertTrue(downloadFailureMessage(DownloadManager.ERROR_HTTP_DATA_ERROR).contains("网络"))
        assertTrue(downloadFailureMessage(404).contains("地址已失效"))
        assertTrue(downloadFailureMessage(503).contains("服务暂不可用"))
        assertTrue(downloadPauseMessage(DownloadManager.PAUSED_QUEUED_FOR_WIFI).contains("Wi-Fi"))
        assertTrue(downloadPauseMessage(DownloadManager.PAUSED_WAITING_TO_RETRY).contains("系统"))
    }
    @Test fun installationNeverDisplaysArbitraryExceptionMessages() {
        assertFalse(installationError(IllegalStateException("secret raw diagnostic")).contains("secret"))
        assertTrue(installationError(IOException("socket stack")).contains("存储空间"))
        assertEquals(InstallIssue.CHECKSUM.text, installationError(InstallFailure(InstallIssue.CHECKSUM)))
        assertFalse(failureFeedback(AppFailure(FailureOperation.UPDATE, FailureStage.RESPONSE, FailureReason.INVALID_DATA), FailureOperation.UPDATE).text.contains("检查网络"))
    }
}
