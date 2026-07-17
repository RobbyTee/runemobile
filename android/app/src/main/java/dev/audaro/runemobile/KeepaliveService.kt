package dev.audaro.runemobile

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
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * PARITY-P0-1 (`android-background-keepalive`): keep the JVM + game socket alive while the app is
 * backgrounded, mirroring iOS's UIBackgroundTask + silent-AVAudioEngine keepalive
 * (ViewController.m onEnterBackground). iOS uses an audio engine because that is its only sanctioned
 * way to stay resident; the Android idiom for the same guarantee is a started foreground Service
 * holding an ongoing notification.
 *
 * MainActivity.onStop starts this ONLY when (a) the "keep session alive" pref is on and (b) the
 * client is logged in; onStart stops it again. The render throttle (JvmLauncher.setPaused(true)) is
 * left exactly as-is and is battery-correct — this service only prevents process suspension so the
 * client tick + game socket survive the background window. An idle-stop deadline (armed by
 * MainActivity, mirroring iOS's kBgIdleStopSeconds) tears the service down so a forgotten
 * backgrounded app doesn't hold the process indefinitely.
 */
class KeepaliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel(this)
        val n = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            Log.i(TAG, "keepalive: foreground service started")
        } catch (t: Throwable) {
            // An OEM battery manager or an FGS-start restriction can refuse the promotion — treat it
            // as a soft failure (the reconnect overlay self-heals on return) rather than a crash.
            Log.w(TAG, "keepalive: startForeground refused: ${t.message}")
            stopSelf()
        }
        // START_NOT_STICKY: if the OS kills us under memory pressure the process is already gone, so
        // don't resurrect a keepalive for it — the reconnect overlay is the fallback on return.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "keepalive: foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepaliveService"
        private const val CHANNEL = "runelite_keepalive"
        private const val NOTIF_ID = 0x2A1

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL) != null) return
            val ch =
                NotificationChannel(CHANNEL, "Background session", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Shown while your game session is kept alive in the background"
                        setShowBadge(false)
                    }
            mgr.createNotificationChannel(ch)
        }

        private fun buildNotification(ctx: Context): Notification {
            val openSelf =
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            val pi =
                openSelf?.let {
                    PendingIntent.getActivity(
                        ctx,
                        0,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            return NotificationCompat
                .Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rune)
                .setContentTitle("Keeping your session alive")
                .setContentText("Tap to return to the game")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
        }
    }
}
