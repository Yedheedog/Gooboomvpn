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
        val server = intent.getStringExtra(EXTRA_SERVER) ?: "VPN Gate relay"
        val profile = intent.getByteArrayExtra(EXTRA_PROFILE)
        VpnConnectionState.update(VpnUiState(VpnPhase.STARTING, server, "Preparing secure connection…"))
        startForeground(NOTIFICATION_ID, notification("Preparing connection to $server"))

        try {
            require(profile != null && profile.isNotEmpty()) { "The selected relay returned an empty OpenVPN profile." }
            // This check is deliberately fail-closed. The packaged JNI shim reports false until
            // the upstream OpenVPN 3 core and Android TunBuilder adapter are linked into the APK.
            if (!OpenVpn3Native.isCoreAvailable()) {
                throw IllegalStateException(
                    "OpenVPN 3 core is not packaged yet. Android VPN permission and service lifecycle are ready, but no tunnel will be started."
                )
            }
            // The actual OpenVPN 3 client must be started here on a worker thread. It must call
            // protect(fd) for its transport socket and use TunBuilder callbacks to create/close
            // the Android TUN interface. Never mark CONNECTED from this service's start request.
            throw IllegalStateException("OpenVPN 3 Android TunBuilder/JNI adapter is not linked.")
        } catch (t: Throwable) {
            VpnConnectionState.update(VpnUiState(VpnPhase.ERROR, server, t.message ?: "VPN startup failed"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } finally {
            // Profiles contain inline certificates/keys. Do not persist or log them; clear the
            // byte buffer as soon as the native handoff (or fail-closed check) is complete.
            profile?.fill(0)
        }
    }

    private fun stopVpn() {
        VpnConnectionState.update(VpnUiState(VpnPhase.DISCONNECTING, message = "Disconnecting…"))
        // Once the native core is linked, invoke its thread-safe stop() before closing TUN.
        stopForeground(STOP_FOREGROUND_REMOVE)
        VpnConnectionState.update(VpnUiState())
        stopSelf()
    }

    override fun onRevoke() {
        // Android can revoke VPN consent at any time. Always tear down and release the TUN/core.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
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