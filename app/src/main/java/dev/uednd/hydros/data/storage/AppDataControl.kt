package dev.uednd.hydros.data.storage

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface AppDataControl {
    suspend fun sizeBytes(): Long
    fun clearAllData(): Boolean
}

class AndroidAppDataControl(private val context: Context) : AppDataControl {
    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val internal = File(context.applicationInfo.dataDir)
        // Count user data, excluding installed code and compiled code caches.
        val roots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir,
            File(internal, "shared_prefs"), File(internal, "databases")) +
            context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        roots.distinctBy { it.absolutePath }.sumOf { root ->
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    override fun clearAllData(): Boolean = context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
}
