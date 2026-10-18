package dev.uednd.hydros.data.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

interface AppInstaller {
    /** Published by the installer so the UI follows the native state instead of polling it. */
    val state: StateFlow<InstallState>

    suspend fun start(release: AppRelease): InstallState
    suspend fun refresh(): InstallState
    suspend fun continueInstall(): InstallState
    suspend fun cancel()

    /** Settings or system-confirmation intent to launch once; null when nothing is pending. */
    fun takeLaunchIntent(): android.content.Intent?

    /** Puts a one-shot intent back when the Activity could not launch it. */
    fun restoreLaunchIntent(intent: android.content.Intent)
}

enum class InstallIssue(val text: String) {
    NO_ASSET("此版本未提供适用于当前设备的更新包"),
    SAVE_TASK("无法保存更新任务，请稍后重试"), MISSING_FILE("更新任务或文件已失效，请重新下载"),
    SIZE("更新包不完整，请取消后重新下载"), CHECKSUM("更新包校验失败，请取消后重新下载"),
    INVALID_PACKAGE("无法读取更新包，请取消后重新下载"), VERSION("更新包与当前应用或版本不一致，请重新检查更新"),
    SIGNATURE("更新包签名与当前应用不一致，无法安装，请使用官方发布的更新包"), SYSTEM("此版本不支持当前 Android 系统")
}

class InstallFailure(val issue: InstallIssue) : Exception()

internal fun installCheck(condition: Boolean, issue: InstallIssue) { if (!condition) throw InstallFailure(issue) }

fun installationError(error: Exception): String = when (error) {
    is InstallFailure -> error.issue.text
    is ActivityNotFoundException -> "无法启动系统安装器，请检查系统安装功能后重试"
    is SecurityException -> "无法访问更新文件或安装功能，请检查应用权限后重试"
    is IOException -> "无法读取或保存更新文件，请检查存储空间后重试"
    else -> "安装更新未完成，请稍后重试"
}

fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请释放空间后重新下载"
    DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "无法保存更新包，请检查存储空间后重新下载"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载连接中断，请检查网络后重新下载"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "更新下载地址暂不可用，请稍后重新检查更新"
    DownloadManager.ERROR_CANNOT_RESUME -> "下载无法继续，请重新下载更新包"
    429 -> "更新下载服务繁忙，请稍后重新下载"
    in 400..499 -> "更新下载地址已失效，请重新检查更新"
    in 500..599 -> "更新下载服务暂不可用，请稍后重试"
    else -> "更新包下载未完成，请稍后重新下载"
}

fun downloadPauseMessage(reason: Int): String = when (reason) {
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "下载已暂停，请连接网络后等待系统继续"
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "下载正在等待 Wi-Fi，请连接 Wi-Fi 后继续"
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "下载已暂停，系统正在等待重试"
    else -> "下载已暂停，等待系统恢复"
}

enum class InstallPhase(val label: String) {
    DOWNLOADING("正在下载更新…"), WAITING_NETWORK("下载已暂停，等待网络或系统恢复"),
    VERIFYING("正在校验更新…"), INSTALLING("正在安装更新…"),
    AWAITING_PERMISSION("等待安装权限"), AWAITING_CONFIRMATION("等待系统安装确认")
}

data class InstallState(
    val phase: InstallPhase? = null,
    val percent: Int? = null,
    val error: String? = null,
    val statusMessage: String? = null,
    val canCancel: Boolean = false,
)
