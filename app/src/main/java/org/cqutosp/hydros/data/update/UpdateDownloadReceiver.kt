package org.cqutosp.hydros.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The download id is only a hint: the manager re-queries the system and checks the saved task. */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        AndroidUpdateManager.get(context)
            .downloadCompleted(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
    }
}
