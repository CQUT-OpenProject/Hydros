package org.cqutosp.hydros.data.update

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Owns the one authorized update independently of the Activity: download, verification, the
 * PackageInstaller session and its result callback all continue through receivers and the
 * persisted job, so the user only taps the system confirmation (or nothing on Android 13+).
 */
@SuppressLint("StaticFieldLeak", "ApplySharedPref")
class AndroidUpdateManager private constructor(context: Context) : AppInstaller {
    private val context = context.applicationContext
    private val downloads = this.context.getSystemService(DownloadManager::class.java)
    private val jobs = this.context.getSystemService(JobScheduler::class.java)
    private val installer = this.context.packageManager.packageInstaller
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "hydros-update") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var task = load()
    @Volatile private var confirmation: Intent? = null
    @Volatile private var permissionPrompt = false
    @Volatile private var successReported = false
    @Volatile private var cancelRequested = false
    private var progress: Job? = null
    private val mutable = MutableStateFlow(snapshot())

    /** Serializes the state machine with the UI; receivers and the job resume through the same dispatcher. */
    override val state: StateFlow<InstallState> = mutable.asStateFlow()

    init {
        if (task.version.isNotEmpty() && installedVersion() == task.version) clearTask(completed = true)
        if (task.phase == UpdatePhase.DOWNLOADING || task.phase == UpdatePhase.WAITING_NETWORK) startProgress()
        scope.launch { sweepStaleFiles() }
    }

    override suspend fun start(release: AppRelease): InstallState = withContext(dispatcher) { startTask(release) }

    override suspend fun refresh(): InstallState = withContext(dispatcher) { reconcile() }

    override suspend fun continueInstall(): InstallState = withContext(dispatcher) { continueTask() }

    override suspend fun cancel() {
        // Signal the running copy before waiting for the dispatcher; otherwise the cancel tap
        // would queue behind the very work it is meant to stop.
        cancelRequested = true
        try {
            withContext(dispatcher) { cancelTask() }
        } finally {
            cancelRequested = false
        }
    }

    override fun consumeInstallCompleted(): Boolean {
        val completed = prefs.getBoolean(KEY_COMPLETED, false)
        if (completed) prefs.edit().remove(KEY_COMPLETED).commit()
        return completed
    }

    /** Called on the main thread; consumes the one-shot intent so it cannot be launched twice. */
    override fun takeLaunchIntent(): Intent? {
        if (permissionPrompt) {
            permissionPrompt = false
            if (!hasInstallPermission()) {
                return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            }
        }
        return confirmation.also { confirmation = null }
    }

    /** Puts a one-shot intent back when startActivity() failed, so it is not silently lost. */
    override fun restoreLaunchIntent(intent: Intent) {
        if (intent.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) {
            if (task.active && !hasInstallPermission()) permissionPrompt = true
        } else if (task.active && task.phase == UpdatePhase.AWAITING_CONFIRMATION) {
            confirmation = intent
        }
    }

    internal fun downloadCompleted(downloadId: Long) {
        scope.launch { if (task.downloadId == downloadId) reconcile() }
    }

    internal fun installationResult(taskId: String, sessionId: Int, status: Int, intent: Intent?) {
        scope.launch { handleInstallationResult(taskId, sessionId, status, intent) }
    }

    internal fun packageReplaced() {
        scope.launch { reconcile() }
    }

    internal suspend fun processTask(taskId: String) = withContext(dispatcher) { stage(taskId) }

    private fun startTask(release: AppRelease): InstallState {
        reconcile()
        if (task.active) return snapshot()
        val asset = release.installAsset(Build.SUPPORTED_ABIS.toList()) ?: throw InstallFailure(InstallIssue.NO_ASSET)
        if (apkDirectory() == null) throw InstallFailure(InstallIssue.SAVE_TASK)
        val reusable = task.phase == UpdatePhase.FAILED && task.version == release.version && task.url == asset.url &&
            apkFile(task.taskId)?.let { it.isFile && (asset.size <= 0 || it.length() == asset.size) } == true
        cancelJob()
        removeDownload()
        abandonSession()
        confirmation = null
        permissionPrompt = false
        successReported = false
        if (!reusable) apkFile(task.taskId)?.delete()
        val taskId = if (reusable) task.taskId else UUID.randomUUID().toString()
        task = UpdateTask(taskId = taskId, version = release.version, url = asset.url, size = asset.size,
            sha256 = asset.sha256, phase = UpdatePhase.AWAITING_PERMISSION, percent = 0)
        persist()
        // The prompt flag must be visible before the phase is published, or the observer that
        // launches the settings page may run first and find nothing pending.
        if (hasInstallPermission()) prepare() else { permissionPrompt = true; publish() }
        return snapshot()
    }

    private fun reconcile(): InstallState {
        if (!task.active) return snapshot()
        if (installedVersion() == task.version) {
            clearTask(completed = true)
            return snapshot()
        }
        when (task.phase) {
            UpdatePhase.AWAITING_PERMISSION -> if (hasInstallPermission()) prepare()
            UpdatePhase.DOWNLOADING, UpdatePhase.WAITING_NETWORK -> queryDownload()
            UpdatePhase.VERIFYING -> scheduleJob()
            UpdatePhase.INSTALLING -> inspectInstalling()
            UpdatePhase.AWAITING_CONFIRMATION -> {
                // A just-committed session can be momentarily invisible; confirm before failing.
                if (!successReported && runCatching { installer.getSessionInfo(task.sessionId) }.getOrNull() == null) {
                    scope.launch { delay(CONFIRMATION_GRACE_MS); confirmSessionLost() }
                }
            }
            else -> Unit
        }
        return snapshot()
    }

    /** Permission was granted (or revoked) while the user was away. */
    private fun prepare() {
        if (!hasInstallPermission()) {
            transition(UpdatePhase.AWAITING_PERMISSION)
            return
        }
        if (task.phase == UpdatePhase.DOWNLOADING || task.phase == UpdatePhase.WAITING_NETWORK) return
        val file = apkFile(task.taskId)
        if (file != null && file.isFile && task.size > 0 && file.length() == task.size) {
            transition(UpdatePhase.VERIFYING)
            scheduleJob()
        } else {
            enqueueDownload()
        }
    }

    private fun enqueueDownload() {
        val file = apkFile(task.taskId)
        if (file == null) {
            fail(InstallIssue.SAVE_TASK.text)
            return
        }
        file.parentFile?.mkdirs()
        // A crash between enqueue() and persisting its id must not enqueue a second request,
        // and an in-flight request must not have its destination deleted underneath it.
        val recovered = runCatching {
            downloads.query(DownloadManager.Query()).use { cursor ->
                var found = -1L
                while (cursor.moveToNext()) {
                    val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (Uri.fromFile(file).toString() == local) {
                        found = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    }
                }
                found
            }
        }.getOrDefault(-1L)
        if (recovered >= 0) {
            task = task.copy(downloadId = recovered, phase = UpdatePhase.DOWNLOADING, percent = 0, status = null)
            persist()
            publish()
            startProgress()
            return
        }
        file.delete()
        val request = DownloadManager.Request(Uri.parse(task.url))
            .setTitle("Hydros ${task.version}")
            .setMimeType(APK_MIME)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        val id = try {
            downloads.enqueue(request)
        } catch (error: Exception) {
            fail(installationError(error))
            return
        }
        task = task.copy(downloadId = id, phase = UpdatePhase.DOWNLOADING, percent = 0, status = null)
        persist()
        publish()
        startProgress()
    }

    private fun startProgress() {
        if (progress?.isActive == true) return
        progress = scope.launch {
            while (task.phase == UpdatePhase.DOWNLOADING || task.phase == UpdatePhase.WAITING_NETWORK) {
                delay(1_000)
                reconcile()
            }
        }
    }

    private fun queryDownload() {
        if (task.downloadId < 0) {
            enqueueDownload()
            return
        }
        try {
            downloads.query(DownloadManager.Query().setFilterById(task.downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    fail("更新任务或文件已失效，请重新下载")
                    return
                }
                fun column(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                when (column(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        transition(UpdatePhase.VERIFYING)
                        scheduleJob()
                    }
                    DownloadManager.STATUS_FAILED -> fail(downloadFailureMessage(column(DownloadManager.COLUMN_REASON).toInt()))
                    DownloadManager.STATUS_PAUSED -> update(task.copy(phase = UpdatePhase.WAITING_NETWORK,
                        status = downloadPauseMessage(column(DownloadManager.COLUMN_REASON).toInt())))
                    else -> {
                        val total = column(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val bytes = column(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        update(task.copy(phase = UpdatePhase.DOWNLOADING,
                            percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else null,
                            status = null))
                    }
                }
            }
        } catch (error: Exception) {
            fail(installationError(error))
        }
    }

    private fun scheduleJob() {
        if (task.phase != UpdatePhase.VERIFYING) return
        val pending = jobs.getPendingJob(JOB_ID)
        if (pending != null) {
            if (pending.extras?.getString(EXTRA_TASK) == task.taskId) return
            jobs.cancel(JOB_ID)
        }
        val extras = PersistableBundle().apply { putString(EXTRA_TASK, task.taskId) }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateInstallJob::class.java))
            .setPersisted(true)
            .setMinimumLatency(0)
            .setExtras(extras)
            .build()
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) fail("无法启动后台安装任务，请重试")
    }

    private fun continueTask(): InstallState {
        reconcile()
        when (task.phase) {
            UpdatePhase.AWAITING_PERMISSION -> if (hasInstallPermission()) prepare() else permissionPrompt = true
            UpdatePhase.AWAITING_CONFIRMATION -> if (confirmation == null) {
                abandonSession()
                task = task.copy(phase = UpdatePhase.VERIFYING, committed = false, sessionId = -1)
                persist()
                publish()
                scheduleJob()
            }
            else -> Unit
        }
        return snapshot()
    }

    private fun cancelTask() {
        if (!task.cancellable) return
        clearTask()
    }

    private fun stage(taskId: String) {
        if (!task.active || task.taskId != taskId || task.phase != UpdatePhase.VERIFYING) return
        try {
            if (!hasInstallPermission()) {
                transition(UpdatePhase.AWAITING_PERMISSION)
                return
            }
            val file = apkFile(taskId) ?: run {
                fail(InstallIssue.MISSING_FILE.text)
                return
            }
            if (!file.isFile) {
                fail(InstallIssue.MISSING_FILE.text)
                return
            }
            if (task.size > 0 && file.length() != task.size) {
                fail(InstallIssue.SIZE.text)
                return
            }
            val versionCode = verifyArchive(file)
            task = task.copy(versionCode = versionCode)
            persist()
            abandonSession()
            if (!hasInstallPermission()) {
                transition(UpdatePhase.AWAITING_PERMISSION)
                return
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = try {
                installer.createSession(params)
            } catch (error: Exception) {
                fail(installationError(error))
                return
            }
            task = task.copy(sessionId = sessionId)
            persist()
            var committed = false
            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        copy(file, output, taskId)
                        session.fsync(output)
                    }
                    if (!owns(taskId)) return
                    if (!hasInstallPermission()) {
                        abandonSession()
                        transition(UpdatePhase.AWAITING_PERMISSION)
                        return
                    }
                    val pending = resultIntent(taskId, sessionId)
                    task = task.copy(committed = true, phase = UpdatePhase.INSTALLING,
                        committedAt = System.currentTimeMillis())
                    persist()
                    publish()
                    try {
                        session.commit(pending.intentSender)
                        committed = true
                    } catch (error: Exception) {
                        val sealed = runCatching { installer.getSessionInfo(sessionId)?.isSealed }.getOrNull() == true
                        if (!sealed) {
                            task = task.copy(committed = false)
                            persist()
                        }
                        throw error
                    }
                }
            } catch (error: Exception) {
                if (!committed) {
                    abandonSession()
                    throw error
                }
            }
        } catch (error: CancellationException) {
            when {
                cancelRequested -> clearTask()
                task.active && task.taskId == taskId && !task.committed -> {
                    abandonSession()
                    transition(UpdatePhase.VERIFYING)
                    scheduleJob()
                }
            }
        } catch (error: InstallFailure) {
            fail(error.issue.text)
        } catch (error: SecurityException) {
            fail("无法安装更新，请检查系统安装权限后重试")
        } catch (error: IOException) {
            fail("无法读取或保存更新文件，请检查存储空间后重试")
        } catch (error: Exception) {
            fail(installationError(error))
        }
    }

    private fun verifyArchive(file: File): Long {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw InstallFailure(InstallIssue.INVALID_PACKAGE)
        installCheck(archive.packageName == context.packageName, InstallIssue.INVALID_PACKAGE)
        installCheck(archive.versionName == task.version, InstallIssue.VERSION)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        installCheck(archive.longVersionCode > installed.longVersionCode, InstallIssue.VERSION)
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty()
        installCheck(signers.isNotEmpty() && signers.toSet() == installed.signingInfo?.apkContentsSigners.orEmpty().toSet(),
            InstallIssue.SIGNATURE)
        installCheck(archive.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true, InstallIssue.SYSTEM)
        return archive.longVersionCode
    }

    /** The session write doubles as the integrity check, so the verified bytes are the installed bytes. */
    private fun copy(file: File, output: OutputStream, taskId: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelRequested || !owns(taskId)) throw CancellationException("update canceled")
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (task.size > 0 && total > task.size) throw InstallFailure(InstallIssue.SIZE)
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (task.size > 0) installCheck(total == task.size, InstallIssue.SIZE)
        task.sha256?.let { expected ->
            installCheck(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true), InstallIssue.CHECKSUM)
        }
    }

    private fun recheckSilentInstall() {
        if (task.phase != UpdatePhase.INSTALLING || !successReported) return
        reconcile()
        if (task.phase == UpdatePhase.INSTALLING &&
            runCatching { installer.getSessionInfo(task.sessionId) }.getOrNull() == null) {
            fail("安装未完成，请重新下载")
        }
    }

    private fun inspectInstalling() {
        val info = runCatching { installer.getSessionInfo(task.sessionId) }.getOrNull()
        if (info == null) {
            if (!successReported) fail("安装未完成，请重新下载")
            return
        }
        when {
            !info.isSealed -> {
                abandonSession()
                transition(UpdatePhase.VERIFYING)
                scheduleJob()
            }
            task.committed && !successReported && task.committedAt > 0 &&
                System.currentTimeMillis() - task.committedAt > INSTALL_WAIT_MS ->
                fail("安装未完成，请重新下载")
        }
    }

    private fun confirmSessionLost() {
        if (task.phase != UpdatePhase.AWAITING_CONFIRMATION) return
        if (runCatching { installer.getSessionInfo(task.sessionId) }.getOrNull() == null) {
            fail("已取消安装，可手动重试")
        }
    }

    private fun handleInstallationResult(taskId: String, sessionId: Int, status: Int, intent: Intent?) {
        if (!task.active || task.taskId != taskId || task.sessionId != sessionId) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (intent == null) {
                    fail("无法启动系统安装器，请重试")
                    return
                }
                confirmation = intent
                task = task.copy(phase = UpdatePhase.AWAITING_CONFIRMATION, committed = false)
                persist()
                publish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                successReported = true
                task = task.copy(phase = UpdatePhase.INSTALLING, committed = true,
                    committedAt = System.currentTimeMillis())
                persist()
                publish()
                // A silent self-update may report success before PackageManager exposes the new
                // version, and its session is already gone. Reconcile once more, then fail so the
                // UI never stays on an unrecoverable "installing" state.
                scope.launch { delay(SUCCESS_RECHECK_MS); recheckSilentInstall() }
                reconcile()
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail("存储空间不足，请清理后重试")
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail("已取消安装，可手动重试")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail("系统阻止安装，请检查安装权限和设备策略")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> fail(InstallIssue.VERSION.text)
            else -> fail("安装失败，请重试或检查系统限制")
        }
    }

    private fun transition(phase: UpdatePhase) {
        task = task.copy(
            phase = phase,
            error = null,
            percent = if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.WAITING_NETWORK) task.percent else null,
            status = if (phase == UpdatePhase.WAITING_NETWORK) task.status else null,
        )
        persist()
        publish()
    }

    private fun fail(message: String) {
        if (!task.active) return
        val committed = task.committed
        permissionPrompt = false
        confirmation = null
        cancelJob()
        removeDownload()
        if (!committed) abandonSession()
        // A verified APK is kept so retrying an aborted system confirmation skips the download.
        if (task.versionCode <= 0) apkFile(task.taskId)?.delete()
        task = task.copy(phase = UpdatePhase.FAILED, error = message, sessionId = -1, downloadId = -1,
            committed = false, committedAt = 0, percent = null, status = null)
        persist()
        publish()
        sweepStaleFiles()
    }

    private fun clearTask(completed: Boolean = false) {
        permissionPrompt = false
        confirmation = null
        successReported = false
        cancelJob()
        removeDownload()
        abandonSession()
        apkFile(task.taskId)?.delete()
        task = UpdateTask()
        // Installation replaces the process before the UI can observe success, so leave a
        // one-shot marker for the next launch to report instead of guessing from an idle state.
        if (completed) prefs.edit().putBoolean(KEY_COMPLETED, true).commit()
        prefs.edit().remove(KEY).commit()
        publish()
        sweepStaleFiles()
    }

    private fun cancelJob() {
        jobs.cancel(JOB_ID)
    }

    private fun removeDownload() {
        val id = task.downloadId
        if (id >= 0) runCatching { downloads.remove(id) }
    }

    private fun abandonSession() {
        val sessionId = task.sessionId
        if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
        if (sessionId >= 0 && task.sessionId == sessionId) task = task.copy(sessionId = -1, committed = false)
        confirmation = null
    }

    private fun sweepStaleFiles() {
        val directory = apkDirectory() ?: return
        val keep = if (task.taskId.isEmpty()) null else apkFile(task.taskId)
        directory.listFiles().orEmpty().filter { it != keep && it.name.endsWith(".apk") }.forEach { it.delete() }
    }

    private fun owns(taskId: String): Boolean = task.active && task.taskId == taskId

    private fun resultIntent(taskId: String, sessionId: Int): PendingIntent {
        val callback = Intent(context, UpdateResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(EXTRA_TASK, taskId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, callback, flags)
    }

    private fun hasInstallPermission(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun installedVersion(): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    private fun apkDirectory(): File? = context.getExternalFilesDir("updates")

    private fun apkFile(taskId: String): File? =
        if (taskId.isEmpty()) null else apkDirectory()?.let { File(it, "$taskId.apk") }

    private fun snapshot(): InstallState {
        val phase = when (task.phase) {
            UpdatePhase.DOWNLOADING -> InstallPhase.DOWNLOADING
            UpdatePhase.WAITING_NETWORK -> InstallPhase.WAITING_NETWORK
            UpdatePhase.VERIFYING -> InstallPhase.VERIFYING
            UpdatePhase.INSTALLING -> InstallPhase.INSTALLING
            UpdatePhase.AWAITING_PERMISSION -> InstallPhase.AWAITING_PERMISSION
            UpdatePhase.AWAITING_CONFIRMATION -> InstallPhase.AWAITING_CONFIRMATION
            else -> null
        }
        return InstallState(
            phase = phase,
            percent = if (phase == InstallPhase.DOWNLOADING || phase == InstallPhase.WAITING_NETWORK) task.percent else null,
            error = if (task.phase == UpdatePhase.FAILED) task.error else null,
            statusMessage = if (phase == InstallPhase.WAITING_NETWORK) task.status else null,
            canCancel = task.cancellable && phase != null,
        )
    }

    private fun update(next: UpdateTask) {
        if (next == task) return
        task = next
        persist()
        publish()
    }

    private fun persist() {
        if (task.phase == UpdatePhase.IDLE) prefs.edit().remove(KEY).commit()
        else prefs.edit().putString(KEY, json.encodeToString(UpdateTask.serializer(), task)).commit()
    }

    private fun publish() {
        mutable.value = snapshot()
    }

    private fun load(): UpdateTask = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(UpdateTask.serializer(), it) } ?: UpdateTask()
    }.getOrElse { UpdateTask() }

    companion object {
        private const val PREFS = "app-update-v1"
        private const val KEY = "task"
        private const val KEY_COMPLETED = "install-completed"
        private const val JOB_ID = 0x485952
        private const val SUCCESS_RECHECK_MS = 10_000L
        private const val INSTALL_WAIT_MS = 120_000L
        private const val CONFIRMATION_GRACE_MS = 1_500L
        private const val APK_MIME = "application/vnd.android.package-archive"
        const val ACTION_RESULT = "org.cqutosp.hydros.UPDATE_RESULT"
        const val EXTRA_TASK = "taskId"

        @Volatile private var instance: AndroidUpdateManager? = null

        fun get(context: Context): AndroidUpdateManager = instance ?: synchronized(this) {
            instance ?: AndroidUpdateManager(context).also { instance = it }
        }
    }
}
