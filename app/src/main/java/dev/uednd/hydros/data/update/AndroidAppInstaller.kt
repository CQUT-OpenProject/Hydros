package dev.uednd.hydros.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** DownloadManager keeps the task alive across page changes and process recreation. */
class AndroidAppInstaller(context: Context) {
    private val context = context.applicationContext
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app-update-v1", Context.MODE_PRIVATE)
    private val file get() = File(context.getExternalFilesDir(null), "updates/update.apk")
    private val id get() = prefs.getLong("download", -1)

    suspend fun start(release: AppRelease): InstallState = withContext(Dispatchers.IO) {
        check(id == -1L) { "已有更新任务，请先取消后重试" }
        val asset = release.installAsset(Build.SUPPORTED_ABIS.toList()) ?: error("此版本未配置适用于当前设备的 Android 更新包。")
        file.parentFile!!.mkdirs()
        file.delete()
        val request = DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("Hydros ${release.version}")
            .setDescription("正在下载软件更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        val task = downloads.enqueue(request)
        if (!prefs.edit().putLong("download", task).putString("version", release.version)
                .putLong("size", asset.size).putString("sha256", asset.sha256).commit()) {
            downloads.remove(task)
            error("无法保存更新任务，请重试")
        }
        InstallState(InstallPhase.DOWNLOADING)
    }

    suspend fun refresh(): InstallState = withContext(Dispatchers.IO) {
        if (id == -1L) return@withContext InstallState()
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext fail("更新任务或文件已失效，请重新下载")
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            when (long(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_FAILED -> fail("无法下载更新包，请检查网络后重试")
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val version = prefs.getString("version", null)
                    if (version == context.packageManager.getPackageInfo(context.packageName, 0).versionName) {
                        cancel(); InstallState()
                    } else InstallState(if (context.packageManager.canRequestPackageInstalls())
                        InstallPhase.AWAITING_CONFIRMATION else InstallPhase.AWAITING_PERMISSION)
                }
                DownloadManager.STATUS_PAUSED -> InstallState(InstallPhase.WAITING_NETWORK)
                else -> {
                    val total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val bytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    InstallState(InstallPhase.DOWNLOADING, if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else null)
                }
            }
        }
    }

    /** Always reverify before granting the system installer access to the downloaded file. */
    suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        check(id != -1L && file.isFile) { "更新任务或文件已失效，请重新下载" }
        verify()
        if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.updates", file),
                "application/vnd.android.package-archive"
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun verify() {
        val expectedSize = prefs.getLong("size", 0)
        check(expectedSize > 0 && file.length() == expectedSize) { "更新包大小不符，请取消后重新下载" }
        prefs.getString("sha256", null)?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, true)) { "更新包校验失败，请取消后重新下载" }
        }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法读取更新包，请取消后重新下载")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(archive.packageName == context.packageName && archive.versionName == prefs.getString("version", null)
            && archive.longVersionCode > installed.longVersionCode) { "更新包与当前应用或版本不一致" }
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty()
        check(signers.isNotEmpty() && signers.toSet() == installed.signingInfo?.apkContentsSigners.orEmpty().toSet()) {
            "更新包签名与当前应用不一致，无法安装"
        }
        check(archive.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "此版本不支持当前 Android 系统" }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        if (id != -1L) downloads.remove(id)
        prefs.edit().clear().commit()
        file.delete()
        Unit
    }

    private suspend fun fail(message: String): InstallState {
        cancel()
        return InstallState(error = message)
    }
}

enum class InstallPhase(val label: String) {
    DOWNLOADING("正在下载更新…"), WAITING_NETWORK("下载已暂停，等待网络或系统恢复"),
    VERIFYING("正在校验更新…"), AWAITING_PERMISSION("等待安装权限"), AWAITING_CONFIRMATION("等待系统安装确认")
}

data class InstallState(val phase: InstallPhase? = null, val percent: Int? = null, val error: String? = null)
