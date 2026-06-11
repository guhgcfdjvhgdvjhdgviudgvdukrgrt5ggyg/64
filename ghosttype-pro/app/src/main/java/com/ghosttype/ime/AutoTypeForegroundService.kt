package com.ghosttype.ime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ghosttype.R
import com.ghosttype.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class AutoTypeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Scattered integrity check — service-level verification
        try {
            if (com.ghosttype.security.ObfConstants.IS_OBFUSCATED &&
                !com.ghosttype.security.NativeGuard.quickVerify(this)) {
                com.ghosttype.security.Hardener.brick(this)
                return
            }
        } catch (_: Exception) {}
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Auto-Type ready", 0, 0))
        observer = scope.launch {
            AutoTypeEngine.state.collectLatest { s ->
                val title = when {
                    !s.running -> "Auto-Type stopped"
                    s.paused -> "Auto-Type paused (${s.current}/${s.total})"
                    else -> "Auto-Type running (${s.current}/${s.total})"
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(title, s.current, s.total))
                if (!s.running) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(title: String, current: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .apply {
                if (total > 0) setProgress(total, current, false)
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Auto-Type", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "autotype"
        const val NOTIF_ID = 4242

        fun start(ctx: Context) {
            val i = Intent(ctx, AutoTypeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AutoTypeForegroundService::class.java)) }
    }
}
