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
import com.example.vpngate.data.ServerRanker
import com.example.vpngate.data.VpnGateRepository
import com.example.vpngate.data.VpnGateServer
import kotlinx.coroutines.runBlocking

/** Owns Android's VPN foreground-service lifecycle. The native core must own packet I/O. */
class VpnGateVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.example.vpngate.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpngate.action.DISCONNECT"
        const val EXTRA_PROFILE = "inline_profile_bytes"
        const val EXTRA_SERVER = "server_label"
        const val EXTRA_SERVER_IP = "server_ip"
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
    fun onNativeLog(message: String) {
        NativeDiagnosticLog.append(message)
    }

    @androidx.annotation.Keep
    fun onNativeEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        NativeDiagnosticLog.append("EVENT $name error=$error fatal=$fatal $info")
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
            name == "CONNECT_RETURN" && error -> beginAutomaticFailover(info)
            name == "CONNECT_RETURN" -> {
                VpnConnectionState.update(VpnUiState())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            fatal || error -> beginAutomaticFailover(info)
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

        userRequestedDisconnect = false
        attemptedServerIps.clear()
        automaticFailoverAttempts = 0
        activeServerIp = intent.getStringExtra(EXTRA_SERVER_IP)
        activeServerLabel = intent.getStringExtra(EXTRA_SERVER) ?: "VPN Gate relay"
        activeServerIp?.let { attemptedServerIps.add(it) }
        val server = activeServerLabel ?: "VPN Gate relay"
        val profile = intent.getByteArrayExtra(EXTRA_PROFILE)
        VpnConnectionState.update(VpnUiState(VpnPhase.STARTING, server, "Preparing secure connection…"))
        startForeground(NOTIFICATION_ID, notification("Preparing connection to $server"))

        try {
            require(profile != null && profile.isNotEmpty()) { "The selected relay returned an empty OpenVPN profile." }
            check(VpnService.prepare(this) == null) {
                "Android VPN permission is no longer granted. Reopen the app and approve the VPN permission prompt."
            }
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
    private var failoverInProgress = false
    private var userRequestedDisconnect = false
    private var activeServerIp: String? = null
    private var activeServerLabel: String? = null
    private var automaticFailoverAttempts = 0
    private val attemptedServerIps = linkedSetOf<String>()

    /**
     * Recover only from an unexpected fatal OpenVPN event. The service remains foreground
     * while it tears down the old native worker, refreshes sources, and tries another relay.
     * Explicit user disconnects cancel the recovery cycle.
     */
    private fun beginAutomaticFailover(reason: String) {
        if (userRequestedDisconnect || failoverInProgress || serviceDestroyed) return
        if (automaticFailoverAttempts >= ServerRanker.MAX_AUTOMATIC_FAILOVER_ATTEMPTS) {
            finishRecoveryFailure("Automatic recovery stopped after $automaticFailoverAttempts relay attempts. $reason")
            return
        }
        failoverInProgress = true
        nativeStopInProgress = true
        VpnConnectionState.update(
            VpnUiState(VpnPhase.STARTING, activeServerLabel, "Relay failed. Finding another relay…")
        )
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Finding another relay…"))

        Thread {
            var next: VpnGateServer? = null
            var profile: ByteArray? = null
            var failure: String? = null
            try {
                OpenVpn3Native.stop()
                val repository = VpnGateRepository(applicationContext)
                val candidates = runBlocking { repository.fetchServers() }
                next = ServerRanker.nextFailoverCandidate(
                    candidates,
                    currentServerIp = activeServerIp,
                    attemptedServerIps = attemptedServerIps
                )
                if (next == null) {
                    failure = "No untried compatible relay is available."
                } else {
                    profile = repository.decodeProfile(next)
                }
            } catch (t: Throwable) {
                failure = t.message ?: "Could not prepare an alternate relay."
            }

            val selected = next
            val profileBytes = profile
            val errorMessage = failure
            mainHandler.post {
                nativeStopInProgress = false
                failoverInProgress = false
                if (serviceDestroyed || userRequestedDisconnect) {
                    profileBytes?.fill(0)
                    if (!serviceDestroyed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        VpnConnectionState.update(VpnUiState())
                        stopSelf()
                    }
                    return@post
                }
                if (selected == null || profileBytes == null) {
                    finishRecoveryFailure(errorMessage ?: "No alternate relay could be started.")
                    return@post
                }

                automaticFailoverAttempts += 1
                activeServerIp = selected.ip
                activeServerLabel = "\${selected.country} (\${selected.ip})"
                attemptedServerIps.add(selected.ip)
                VpnConnectionState.update(
                    VpnUiState(VpnPhase.STARTING, activeServerLabel, "Trying alternate relay $automaticFailoverAttempts/\${ServerRanker.MAX_AUTOMATIC_FAILOVER_ATTEMPTS}…")
                )
                try {
                    val startError = OpenVpn3Native.start(this, profileBytes)
                    if (startError != null) throw IllegalStateException(startError)
                    nativeSessionActive = true
                } catch (t: Throwable) {
                    nativeSessionActive = false
                    profileBytes.fill(0)
                    beginAutomaticFailover(t.message ?: "Alternate relay startup failed.")
                } finally {
                    profileBytes.fill(0)
                }
            }
        }.start()
    }

    private fun finishRecoveryFailure(message: String) {
        nativeSessionActive = false
        failoverInProgress = false
        nativeStopInProgress = false
        VpnConnectionState.update(VpnUiState(VpnPhase.ERROR, activeServerLabel, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopVpn() {
        userRequestedDisconnect = true
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
        userRequestedDisconnect = true
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