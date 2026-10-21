package org.cqutosp.hydros.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the PackageInstaller session result and the post-replacement notification. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = AndroidUpdateManager.get(context)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> manager.packageReplaced()
            AndroidUpdateManager.ACTION_RESULT -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                manager.installationResult(
                    intent.getStringExtra(AndroidUpdateManager.EXTRA_TASK).orEmpty(),
                    intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                    confirmation,
                )
            }
        }
    }
}
