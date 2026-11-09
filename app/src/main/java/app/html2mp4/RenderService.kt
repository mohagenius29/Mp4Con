package app.html2mp4

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service: keeps the process alive and shows progress while the activity renders. */
class RenderService : Service() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private val nm get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object { const val ACTION_CANCEL = "app.html2mp4.CANCEL"; const val CH = "render"; const val ID = 1 }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "Rendering", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { RenderController.cancel(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build("Starting…", -1, true), type)
        if (RenderController.running) return START_NOT_STICKY
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "html2mp4:render").apply { acquire(6 * 60 * 60 * 1000L) }
        RenderController.run(this)
        scope.launch {
            var last = 0L
            RenderState.ui.collect { s ->
                val now = SystemClock.elapsedRealtime()
                if (now - last < 1000) return@collect
                last = now
                val text = when (s.phase) {
                    Phase.Rendering -> "Frame ${s.frame}" + (if (s.etaSec >= 0) " · ETA ${s.etaSec}s" else "")
                    else -> s.msg
                }
                nm.notify(ID, build(text, if (s.fraction >= 0) (s.fraction * 100).toInt() else -1, true))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, RenderService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HTML2MP4").setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true)
            .setContentIntent(open).setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .apply { if (ongoing) addAction(0, "Cancel", cancel) }.build()
    }

    fun finish(text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(2, NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("HTML2MP4").setContentText(text).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        wake?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    override fun onDestroy() { wake?.let { if (it.isHeld) it.release() }; scope.cancel(); super.onDestroy() }
}
