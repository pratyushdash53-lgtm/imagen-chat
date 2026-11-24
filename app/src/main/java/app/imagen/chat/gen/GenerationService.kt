package app.imagen.chat.gen

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.imagen.chat.ImagenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive, with network access, while images are being generated. Without a
 * foreground service Android freezes background apps and drops their connections, which is why
 * a request used to fail the moment you left the app. The service does no work itself.
 */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val manager = (application as ImagenApp).manager

        if (intent?.action == ACTION_CANCEL) manager.cancelAll()

        try {
            startForeground(
                Notifications.PROGRESS_ID,
                Notifications.progress(this, manager.running.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now; jobs still run in-process.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireLocks()

        if (!observing) {
            observing = true
            val notifications = getSystemService(NotificationManager::class.java)
            scope.launch {
                manager.running.collect { running ->
                    if (running.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        notifications.cancel(Notifications.PROGRESS_ID)
                        // Only stops if no newer start request arrived in the meantime.
                        stopSelf(lastStartId)
                    } else {
                        notifications.notify(Notifications.PROGRESS_ID, Notifications.progress(this@GenerationService, running))
                    }
                }
            }
            // Watchdog: if a job ever disappears without reporting, don't leave the notification stuck.
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    manager.reconcile()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imagen:generation").apply {
                setReferenceCounted(false)
                acquire(20 * 60_000L) // safety net; released as soon as the service stops
            }
        }
        // Without this, Wi-Fi can drop into power-save with the screen off and throttle a long request.
        if (wifiLock?.isHeld != true) {
            try {
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "imagen:generation")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(Notifications.PROGRESS_ID)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.imagen.chat.CANCEL_GENERATION"

        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GenerationService::class.java))
            } catch (e: Exception) {
                // Background start refused by the system; generation still proceeds in-process.
            }
        }
    }
}
