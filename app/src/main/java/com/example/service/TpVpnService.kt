package com.example.service

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.state.VpnStateTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import java.io.FileInputStream
import java.io.IOException

class TpVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "TPMODZ_VPN"
        const val NOTI_ID = 1001
        const val ACTION_STOP = "STOP_VPN"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        startForegroundService()

        VpnStateTracker.setVpnActive(true)

        job?.cancel()
        job = scope.launch {
            runVpnLoop()
        }

        return START_STICKY
    }

    // ================= VPN LOOP =================

    private data class VpnStateConfig(
        val isDisconnect: Boolean,
        val isFakePing: Boolean,
        val pingVal: Int,
        val selected: Set<String>
    )

    private suspend fun runVpnLoop() {
        combine(
            VpnStateTracker.isDisconnecting,
            VpnStateTracker.isFakePingEnabled,
            VpnStateTracker.fakePingValue,
            VpnStateTracker.selectedPackages
        ) { isDisconnect, isFakePing, pingVal, selected ->
            VpnStateConfig(isDisconnect, isFakePing, pingVal, selected)
        }.collectLatest { config ->
            try {
                if (!VpnStateTracker.isVpnActive.value) {
                    closeTunnel()
                    return@collectLatest
                }

                if (config.isDisconnect && config.selected.isNotEmpty()) {
                    VpnStateTracker.setLivePing(999)
                    establishVpn(config.selected)
                    vpnInterface?.let { startPacketDiscarder(it) }
                    // Keep this coroutine suspended and responsive to cancellation
                    delay(Long.MAX_VALUE)
                } else if (config.isFakePing && config.selected.isNotEmpty()) {
                    VpnStateTracker.setLivePing(config.pingVal)
                    establishVpn(config.selected)
                    vpnInterface?.let { startPacketDiscarder(it) }
                    // Keep tunnel open to drop packets as fake ping latency simulation
                    delay(Long.MAX_VALUE)
                } else {
                    VpnStateTracker.setLivePing(15)
                    closeTunnel()
                    delay(Long.MAX_VALUE)
                }
            } finally {
                closeTunnel()
            }
        }
    }

    private fun startPacketDiscarder(pfd: ParcelFileDescriptor) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val input = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32767)
                while (isActive) {
                    val len = input.read(buffer)
                    if (len <= 0) {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                // Ignore since PFD was closed
            }
        }
    }

    // ================= VPN SETUP =================

    private fun establishVpn(selected: Set<String>) {
        if (vpnInterface != null) return

        val builder = Builder()
        builder.setSession("TPMODZ VPN")
        builder.setMtu(1400)

        builder.addAddress("10.1.0.2", 24)
        builder.addRoute("0.0.0.0", 0)

        try {
            builder.addAddress("fd00::1", 64)
            builder.addRoute("::", 0)
        } catch (e: Exception) {
            Log.w("TPVPN", "IPv6 address failed: ${e.message}")
        }

        // REMOVED ALL addDnsServer() CALLS to prevent system DNS re-routing lag.
        // This ensures WiFi/cellular DNS settings remain intact and restore instantly.

        if (selected.isNotEmpty()) {
            for (pkg in selected) {
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w("TPVPN", "App not found: $pkg")
                }
            }
        }

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("TPVPN", "Failed to establish VPN")
        }
    }

    private fun closeTunnel() {
        try {
            readerJob?.cancel()
            readerJob = null
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("TPVPN", "Close error: ${e.message}")
        }
        vpnInterface = null
    }

    // ================= FOREGROUND =================

    private fun startForegroundService() {

        val stopIntent = Intent(this, TpVpnService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TPMODZ VPN Running")
            .setContentText("Protecting selected apps...")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openApp)
            .addAction(0, "STOP", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTI_ID, notification)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    // ================= STOP =================

    private fun stopVpn() {
        try {
            job?.cancel()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e("TPVPN", "Close error: ${e.message}")
        }

        VpnStateTracker.setVpnActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ================= NOTIFICATION CHANNEL =================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TPMODZ VPN",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}