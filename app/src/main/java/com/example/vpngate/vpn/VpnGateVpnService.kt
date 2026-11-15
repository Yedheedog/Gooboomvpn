package com.example.vpngate.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.vpngate.MainActivity

/** Owns Android's VPN foreground-service lifecycle. The native core must own packet I/O. */
class VpnGateVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.example.vpngate.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpngate.action.DISCONNECT"
        const val EXTRA_PROFILE = "inline_profile_bytes"
        const val EXTRA_SERVER = "server_label"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 4101
    }

    /**
     * Called from the native OpenVPN transport worker before a socket connects.
     * Android requires this socket to bypass the VPN being established.
     */
    @androidx.annotation.Keep
    fun protectSocket(fd: Int): Boolean = protect(fd)

    /**
     * Native OpenVPN callbacks arrive on its worker thread. Marshal Android service and
     * notification lifecycle work onto the main thread, and ignore late events after teardown.
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var serviceDestroyed = false
    // Accessed on the main thread; prevents onDestroy() racing an explicit disconnect.

    @androidx.annotation.Keep
    fun onNativeEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        mainHandler.post {
            if (serviceDestroyed) return@post
            handleNativeEvent(name, info, error, fatal)
        }
    }

    private fun handleNativeEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        // An explicit disconnect owns the state transition until nativeStop() has
        // joined the OpenVPN worker. Ignore late CONNECTED/terminal events meanwhile.
        if (nativeStopInProgress) return
        if (name == "CONNECT_RETURN" || fatal || error ||
            name == "EXITING" || name == "DISCONNECTED") {
            nativeSessionActive = false
        }

        when {
            name == "CONNECTED" -> {
                VpnConnectionState.update(VpnUiState(VpnPhase.CONNECTED, message = "VPN tunnel connected"))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Connected"))
            }
            name == "CONNECT_RETURN" && error -> {
                VpnConnectionState.update(VpnUiState(VpnPhase.ERROR, message = info.ifBlank { "OpenVPN connection failed" }))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            name == "CONNECT_RETURN" -> {
                VpnConnectionState.update(VpnUiState())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            fatal || error -> {
                VpnConnectionState.update(VpnUiState(VpnPhase.ERROR, message = info.ifBlank { name }))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            name == "EXITING" || name == "DISCONNECTED" -> {
                VpnConnectionState.update(VpnUiState())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                VpnConnectionState.update(VpnUiState(VpnPhase.STARTING, message = info.ifBlank { name }))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(info.ifBlank { "Connecting…" }))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopVpn()
            ACTION_CONNECT -> startVpn(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startVpn(intent: Intent, startId: Int) {
        // Do not race a new session against the previous session's native teardown.
        // The user can retry once the disconnect completion callback has run.
        if (nativeStopInProgress) {
            VpnConnectionState.update(
                VpnUiState(VpnPhase.DISCONNECTING, message = "Finishing previous disconnect…")
            )
            return
        }

        // Duplicate CONNECT intents must not turn an already-running tunnel into an
        // ERROR or stop its foreground service.
        if (nativeSessionActive) return

        val server = intent.getStringExtra(EXTRA_SERVER) ?: "VPN Gate relay"
        val profile = intent.getByteArrayExtra(EXTRA_PROFILE)
        VpnConnectionState.update(VpnUiState(VpnPhase.STARTING, server, "Preparing secure connection…"))
        startForeground(NOTIFICATION_ID, notification("Preparing connection to $server"))

        try {
            require(profile != null && profile.isNotEmpty()) { "The selected relay returned an empty OpenVPN profile." }
            if (!OpenVpn3Native.isCoreAvailable()) {
                throw IllegalStateException("OpenVPN 3 native core is unavailable.")
            }
            val startError = OpenVpn3Native.start(this, profile)
            if (startError != null) throw IllegalStateException(startError)
            nativeSessionActive = true
            // CONNECTED is set only by the native OpenVPN CONNECTED event callback.
        } catch (t: Throwable) {
            nativeSessionActive = false
            VpnConnectionState.update(VpnUiState(VpnPhase.ERROR, server, t.message ?: "VPN startup failed"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } finally {
            // Profiles contain inline certificates/keys. Do not persist or log them; clear the
            // byte buffer as soon as the native handoff (or fail-closed check) is complete.
            profile?.fill(0)
        }
    }

    private var nativeStopInProgress = false
    private var nativeSessionActive = false

    private fun stopVpn() {
        if (nativeStopInProgress) return
        nativeStopInProgress = true
        VpnConnectionState.update(VpnUiState(VpnPhase.DISCONNECTING, message = "Disconnecting…"))

        // Keep the service alive until OpenVPN has returned from connect() and
        // released its TUN descriptor; do not report disconnected prematurely.
        Thread {
            if (OpenVpn3Native.isCoreAvailable()) OpenVpn3Native.stop()
            mainHandler.post {
                nativeStopInProgress = false
                nativeSessionActive = false
                if (!serviceDestroyed) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    VpnConnectionState.update(VpnUiState())
                    stopSelf()
                }
            }
        }.start()
    }

    override fun onRevoke() {
        // Android can revoke VPN consent at any time. Always tear down and release the TUN/core.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceDestroyed = true
        if (!nativeStopInProgress && OpenVpn3Native.isCoreAvailable()) {
            Thread { OpenVpn3Native.stop() }.start()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("VPN Gate Connect")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}