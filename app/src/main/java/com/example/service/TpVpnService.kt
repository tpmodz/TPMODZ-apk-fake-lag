package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.state.VpnStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException

class TpVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceJob: Job? = null
    private var packetReaderJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var currentAppliedPackages: Set<String> = emptySet()
    
    companion object {
        private const val CHANNEL_ID = "TPMODZ_VPN_CHANNEL"
        private const val NOTIFICATION_ID = 1337
        const val ACTION_STOP = "com.example.service.STOP_VPN"
    }

    override fun onCreate() {
        super.onCreate()
        VpnStateTracker.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        val notification = createNotification("TPMODZ Gaming VPN is running")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                // Fallback for strict OS checking
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        VpnStateTracker.setVpnActive(true)

        serviceJob?.cancel()
        serviceJob = scope.launch {
            runStateUpdates()
        }

        return START_STICKY
    }

    private data class VpnStateConfig(
        val isDisconnect: Boolean,
        val isFakePing: Boolean,
        val pingVal: Int,
        val selected: Set<String>
    )

    private suspend fun runStateUpdates() {
        combine(
            VpnStateTracker.isDisconnecting,
            VpnStateTracker.isFakePingEnabled,
            VpnStateTracker.fakePingValue,
            VpnStateTracker.selectedPackages
        ) { isDisconnect, isFakePing, pingVal, selected ->
            VpnStateConfig(isDisconnect, isFakePing, pingVal, selected)
        }.collectLatest { config ->
            if (!VpnStateTracker.isVpnActive.value) {
                closeTunnel()
                return@collectLatest
            }

            if (config.isDisconnect) {
                VpnStateTracker.setLivePing(999)
                ensureTunnelEstablished(config.selected)
                // We keep the tunnel open continuously while blocking.
                // It will be instantly torn down the moment isDisconnect changes to false (canceled by collectLatest).
                delay(Long.MAX_VALUE)
            } else if (config.isFakePing && config.selected.isNotEmpty()) {
                VpnStateTracker.setLivePing(config.pingVal)
                val blockDuration = config.pingVal.coerceIn(10, 999).toLong()
                while (true) {
                    ensureTunnelEstablished(config.selected)
                    delay(blockDuration)
                    closeTunnel()
                    delay(120)
                }
            } else {
                VpnStateTracker.setLivePing(15)
                closeTunnel()
                // Keep the tunnel closed (internet active).
                // It will instantly reactivate when any state changes (canceled by collectLatest).
                delay(Long.MAX_VALUE)
            }
        }
    }

    @Synchronized
    private fun ensureTunnelEstablished(packages: Set<String>) {
        if (vpnInterface != null && currentAppliedPackages == packages) {
            return
        }

        if (vpnInterface != null) {
            closeTunnel()
        }

        if (packages.isEmpty()) return

        try {
            val builder = Builder()
            builder.setSession("TPMODZ Game Network Control")
            builder.setMtu(1200) // Lower MTU is safer for general carrier configurations

            // IPv4 Allocation and Routing
            builder.addAddress("10.0.0.1", 24)
            builder.addRoute("0.0.0.0", 0)

            // IPv6 Allocation and Routing (Crucial for modern apps/games that bypass IPv4 VPNs)
            try {
                builder.addAddress("fd00::1", 64)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                Log.w("TpVpnService", "Could not configure IPv6: ${e.message}")
            }

            // Route DNS to our local blackhole so domain lookup falls back or stalls instantly
            try {
                builder.addDnsServer("10.0.0.1")
                builder.addDnsServer("fd00::1")
            } catch (e: Exception) {
                Log.w("TpVpnService", "Could not configure DNS: ${e.message}")
            }

            var addedAny = false
            for (p in packages) {
                try {
                    builder.addAllowedApplication(p)
                    addedAny = true
                } catch (e: Exception) {
                    // App is not installed
                }
            }

            if (addedAny) {
                vpnInterface = builder.establish()
                currentAppliedPackages = packages
                startPacketReader()
            }
        } catch (e: Exception) {
            Log.e("TpVpnService", "Error establishing VPN: ${e.message}")
        }
    }

    private fun startPacketReader() {
        packetReaderJob?.cancel()
        val pfd = vpnInterface ?: return
        packetReaderJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(32768)
            val inputStream = java.io.FileInputStream(pfd.fileDescriptor)
            try {
                while (vpnInterface != null) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) {
                        delay(20)
                    }
                    // Discard the packets. This acts as a real, complete firewall drop (blackhole).
                }
            } catch (e: Exception) {
                // Ignore socket/pipe closed
            }
        }
    }

    @Synchronized
    private fun closeTunnel() {
        packetReaderJob?.cancel()
        packetReaderJob = null
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            // Ignore
        }
        vpnInterface = null
        currentAppliedPackages = emptySet()
    }

    private fun stopVpn() {
        VpnStateTracker.setVpnActive(false)
        serviceJob?.cancel()
        closeTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "TPMODZ VPN Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, TpVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TPMODZ Gaming VPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Booster", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
