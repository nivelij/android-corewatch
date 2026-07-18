package com.corewatch

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import com.corewatch.ui.theme.ThemeId
import com.corewatch.ui.theme.paletteFor

/**
 * Foreground service that keeps [SessionCollector] running when the app is off-screen. Shows an
 * ongoing, accent-tinted status notification (static text + a Stop control); tapping it reopens
 * the app.
 */
class MonitoringService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The service only runs while recording (started by the app on "Start recording"); just make
        // sure the reader is ready. Recording itself is begun by the caller, not here.
        SessionCollector.ensureInitialized(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            gracefulStop()
            return START_NOT_STICKY
        }
        // dataSync type works API 29-35 (specialUse would need API 34; minSdk here is 31).
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // STICKY so an OS memory kill (common while a game is foregrounded) respawns the service; on
        // respawn ensureInitialized() sees the persisted recording flag and resumes the same session.
        // An explicit Stop clears that flag and stopSelf()s, so a deliberate stop stays stopped.
        return START_STICKY
    }

    /** Full, deliberate shutdown: stop collection, clear the task from Recents, drop everything. */
    private fun gracefulStop() {
        SessionCollector.stopRecording()
        // Remove the app's card from Recents so Stop truly exits (not just stops the service).
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .appTasks.forEach { it.finishAndRemoveTask() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Swiping the app out of Recents fully stops monitoring and clears the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        gracefulStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Ensure the ongoing notification is gone whether we were stopped via stopService()
        // (double-back exit) or stopSelf() (Stop action).
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().cancel(NOTIF_ID)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_corewatch)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_status))
            .setColor(accentColor())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Tint the notification with the currently selected theme accent (same pref MainActivity uses). */
    private fun accentColor(): Int {
        val prefs = getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        val id = runCatching { ThemeId.valueOf(prefs.getString(THEME_KEY, ThemeId.EMBER.name)!!) }
            .getOrDefault(ThemeId.EMBER)
        return paletteFor(id).accent.toArgb()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.corewatch.action.STOP"
        private const val CHANNEL_ID = "corewatch_monitoring"
        private const val NOTIF_ID = 1
        // Mirrors MainActivity's SharedPreferences keys.
        private const val THEME_PREFS = "corewatch"
        private const val THEME_KEY = "theme"
    }
}
