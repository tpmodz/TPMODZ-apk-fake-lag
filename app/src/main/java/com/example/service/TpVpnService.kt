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
import java.io.FileInputStream
import java.io.IOException

class TpVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
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

    private suspend fun runVpnLoop() {
        try {
            establishVpn()

            val input = FileInputStream(vpnInterface?.fileDescriptor)

            val buffer = ByteArray(32767)

            while (isActive) {
                val len = input.read(buffer)

                if (len > 0) {
                    // DROP PACKET (blackhole style)
                    // bạn có thể xử lý fake lag / filter tại đây
                    VpnStateTracker.setLivePing((50..200).random())
                } else {
                    delay(10)
                }
            }

        } catch (e: Exception) {
            Log.e("TPVPN", "VPN loop error: ${e.message}")
        }
    }

    // ================= VPN SETUP =================

    private fun establishVpn() {
        if (vpnInterface != null) return

        val builder = Builder()

        builder.setSession("TPMODZ VPN")
        builder.setMtu(1400)

        // IPv4 tunnel
        builder.addAddress("10.1.0.2", 24)
        builder.addRoute("0.0.0.0", 0)

        // DNS fake route
        builder.addDnsServer("8.8.8.8")

        // Apply selected apps (IMPORTANT PART)
        val selected = VpnStateTracker.selectedPackages.value

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