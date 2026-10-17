package dev.uednd.hydros.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.uednd.hydros.MainActivity
import dev.uednd.hydros.R
import dev.uednd.hydros.data.HydrosApplication
import dev.uednd.hydros.data.safely
import dev.uednd.hydros.ui.state.SessionUi
import dev.uednd.hydros.ui.state.sessionActionLabel
import dev.uednd.hydros.ui.state.sessionNotificationText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Android host for the session monitor and its foreground notification. */
class SessionWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as HydrosApplication).dependencies
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("session", "设备使用", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification())
        }
    }

    private fun notification(value: SessionUi? = graph.sessions.session.value): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeIntent = Intent(this, SessionWatchService::class.java).setAction("finish")
        val close = PendingIntent.getService(
            this, 1, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "session")
            .setSmallIcon(R.drawable.ic_water)
            .setContentTitle(value?.device?.name ?: "设备使用准备中")
            .setContentText(sessionNotificationText(value, graph.sessions.observation.value))
            .setStyle(Notification.BigTextStyle().bigText(sessionNotificationText(value, graph.sessions.observation.value)))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, value?.let(::sessionActionLabel) ?: "打开应用处理", close).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "finish") {
            scope.launch {
                try { graph.restore(); graph.sessions.finish(graph.auth.credentials.value) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    graph.sessions.reportActionFailure(error)
                    getSystemService(NotificationManager::class.java).notify(1, notification())
                }
            }
        }
        if (watch == null) {
            watch = scope.launch {
                try {
                    safely {
                        graph.restore()
                        val notificationUpdates = launch {
                            combine(graph.sessions.session, graph.sessions.observation) { session, _ -> session }.collect { session ->
                                if (session != null) getSystemService(NotificationManager::class.java).notify(1, notification(session))
                            }
                        }
                        try { graph.sessionMonitor().watch {} } finally { notificationUpdates.cancel() }
                    }
                } finally {
                    watch = null
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionWatchService::class.java))
        }
    }
}
