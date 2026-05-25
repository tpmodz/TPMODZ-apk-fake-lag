package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.state.VpnStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var dismissJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tpmodz_overlay_channel",
                "TPMODZ Floating Controller",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running in background for floating remote controller"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startNotificationForeground() {
        createNotificationChannel()
        
        val notificationIntent = Intent(this, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(this, "tpmodz_overlay_channel")
            .setContentTitle("TPMODZ Active")
            .setContentText("The floating controller is running in the background.")
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
            
        try {
            startForeground(8888, notification)
        } catch (e: Exception) {
            // handle exception
        }
    }

    override fun onCreate() {
        super.onCreate()
        startNotificationForeground()
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            // Ignore if already restored
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        VpnStateTracker.init(this)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            VpnStateTracker.setOverlayActive(this, false)
            stopSelf()
            return
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)

            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF00FF66), // neon green
                        secondary = Color(0xFF9D4EDD), // neon purple
                        background = Color(0xFF0A0812)
                    )
                ) {
                    FloatingOverlayUI()
                }
            }
        }

        try {
            windowManager.addView(overlayView, layoutParams)
            VpnStateTracker.setOverlayActive(this, true)
        } catch (e: Exception) {
            VpnStateTracker.setOverlayActive(this, false)
            stopSelf()
        }
    }

    @Composable
    fun FloatingOverlayUI() {
        var isExpanded by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val isVpnActive by VpnStateTracker.isVpnActive.collectAsState()
        val isFakePing by VpnStateTracker.isFakePingEnabled.collectAsState()
        val livePing by VpnStateTracker.currentLivePing.collectAsState()
        val isDisconnecting by VpnStateTracker.isDisconnecting.collectAsState()
        val disconnectCountMillis by VpnStateTracker.disconnectMillisLeft.collectAsState()
        val cutSeconds by VpnStateTracker.cutSeconds.collectAsState()
        val cutMillis by VpnStateTracker.cutMillis.collectAsState()
        val isInfinite by VpnStateTracker.isInfinite.collectAsState()

        // Cyberspace styling colors
        val purpleNeon = Color(0xFF9146FF)
        val greenNeon = Color(0xFF00FF66)
        val warningRed = Color(0xFFFF2E56)
        val darkBg = Color(0xFF0D0B16)

        Row(
            modifier = Modifier
                .pointerInput(isExpanded) {
                    if (!isExpanded) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var totalDragX = 0f
                            var totalDragY = 0f
                            var isDragging = false
                            var longPressTriggered = false

                            // We check for 2-second hold (2000 ms)
                            val longPressJob = scope.launch {
                                delay(2000)
                                if (!isDragging) {
                                    longPressTriggered = true
                                    isExpanded = true
                                }
                            }

                            do {
                                val event = awaitPointerEvent()
                                val moveChange = event.changes.firstOrNull()
                                if (moveChange != null && moveChange.pressed) {
                                    val dragAmountX = moveChange.position.x - moveChange.previousPosition.x
                                    val dragAmountY = moveChange.position.y - moveChange.previousPosition.y
                                    
                                    totalDragX += dragAmountX
                                    totalDragY += dragAmountY
                                    
                                    if (!isDragging && (Math.abs(totalDragX) > 12f || Math.abs(totalDragY) > 12f)) {
                                        isDragging = true
                                        longPressJob.cancel()
                                    }
                                    
                                    if (isDragging) {
                                        moveChange.consume()
                                        layoutParams.x += dragAmountX.toInt()
                                        layoutParams.y += dragAmountY.toInt()
                                        windowManager.updateViewLayout(overlayView, layoutParams)
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            longPressJob.cancel()

                            if (!isDragging && !longPressTriggered) {
                                if (VpnStateTracker.isDisconnecting.value) {
                                    cancelDisconnectCut()
                                } else {
                                    triggerDisconnectCut()
                                }
                            }

                            clampToScreen()
                        }
                    } else {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            do {
                                val event = awaitPointerEvent()
                                val moveChange = event.changes.firstOrNull()
                                if (moveChange != null && moveChange.pressed) {
                                    val dragAmountX = moveChange.position.x - moveChange.previousPosition.x
                                    val dragAmountY = moveChange.position.y - moveChange.previousPosition.y
                                    moveChange.consume()
                                    layoutParams.x += dragAmountX.toInt()
                                    layoutParams.y += dragAmountY.toInt()
                                    windowManager.updateViewLayout(overlayView, layoutParams)
                                }
                            } while (event.changes.any { it.pressed })
                            clampToScreen()
                        }
                    }
                }
                .wrapContentSize()
        ) {
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                },
                label = "OverlayTransition"
            ) { expanded ->
                if (!expanded) {
                    // Small circular state (Closed/Collapsed icon)
                    val activeColor = if (isDisconnecting) warningRed else greenNeon
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(12.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF1E1535), darkBg)
                                )
                            )
                            .border(2.dp, activeColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (isDisconnecting) "ON" else "OFF",
                                color = activeColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                            )
                        }
                    }
                } else {
                    // Control Panel mode (Expanded state)
                    Card(
                        modifier = Modifier
                            .width(190.dp)
                            .wrapContentHeight()
                            .shadow(20.dp, RoundedCornerShape(16.dp))
                            .border(1.5.dp, purpleNeon, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = darkBg),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(10.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Title & Collapse button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "TPMODZ CONTROLLER",
                                    color = purpleNeon,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Start
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Collapse",
                                    tint = Color.LightGray,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { isExpanded = false }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Current simulated Ping
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF131024))
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isDisconnecting) "PING: 999ms (CUT)" else "PING: ${livePing}ms",
                                    color = if (isDisconnecting) warningRed else if (isFakePing) purpleNeon else greenNeon,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Switch Boost
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Fake Ping Lg",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Switch(
                                    checked = isFakePing,
                                    onCheckedChange = {
                                        VpnStateTracker.setFakePingEnabled(this@FloatingOverlayService, it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = greenNeon,
                                        checkedTrackColor = Color(0xFF003F17),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color.DarkGray
                                    ),
                                    modifier = Modifier.scale(0.7f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Duration selection row (toggle infinite/custom)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Thời Gian",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF221F3B))
                                        .clickable {
                                            VpnStateTracker.setIsInfinite(this@FloatingOverlayService, !isInfinite)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = if (isInfinite) "Vô hạn" else "${cutSeconds}s ${cutMillis}ms",
                                        color = greenNeon,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Quick Disconnect/Cut button
                            Button(
                                onClick = {
                                    if (VpnStateTracker.isDisconnecting.value) {
                                        cancelDisconnectCut()
                                    } else {
                                        triggerDisconnectCut()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDisconnecting) warningRed else purpleNeon
                                ),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isDisconnecting) {
                                        if (disconnectCountMillis == -1) "CUTTING [Vô hạn]" else "CUTTING [${"%.1f".format(disconnectCountMillis / 1000f)}g]"
                                    } else {
                                        "QUICK CUT"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun triggerDisconnectCut() {
        if (VpnStateTracker.isDisconnecting.value) return

        // Auto-start TPVpnService if it is not currently running
        if (!VpnStateTracker.isVpnActive.value) {
            val intent = Intent(this, TpVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            VpnStateTracker.setVpnActive(true)
        }

        val totalMs = if (VpnStateTracker.isInfinite.value) -1 else (VpnStateTracker.cutSeconds.value * 1000 + VpnStateTracker.cutMillis.value)
        VpnStateTracker.setDisconnecting(true, totalMs)
        dismissJob?.cancel()
        dismissJob = mainScope.launch {
            while ((VpnStateTracker.disconnectMillisLeft.value > 0 || VpnStateTracker.disconnectMillisLeft.value == -1) && VpnStateTracker.isDisconnecting.value) {
                delay(100)
                VpnStateTracker.tickDisconnectMillis(100)
            }
        }
    }

    private fun cancelDisconnectCut() {
        dismissJob?.cancel()
        dismissJob = null
        VpnStateTracker.setDisconnecting(false, 0)
    }

    private fun clampToScreen() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Keep inside screen boundaries without snapping to left/right bounds
        if (layoutParams.x < 0) {
            layoutParams.x = 0
        } else if (layoutParams.x > screenWidth - 100) {
            layoutParams.x = screenWidth - 100
        }

        if (layoutParams.y < 0) {
            layoutParams.y = 0
        } else if (layoutParams.y > screenHeight - 150) {
            layoutParams.y = screenHeight - 150
        }

        try {
            windowManager.updateViewLayout(overlayView, layoutParams)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            windowManager.removeView(overlayView)
            overlayView = null
        }
        VpnStateTracker.setOverlayActive(this, false)
    }

    override fun onDestroy() {
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (e: Exception) {
            // Ignore if lifecycle is already destroyed
        }
        removeOverlay()
        super.onDestroy()
    }
}
