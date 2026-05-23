package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.example.service.FloatingOverlayService
import com.example.service.TpVpnService
import com.example.state.VpnStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Simple App Data Model
data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: android.graphics.drawable.Drawable?
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnStateTracker.init(this)
        enableEdgeToEdge()

        setContent {
            TPModzTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF07050C) // Black/deep purple spacing background
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        activity = this@MainActivity
                    )
                }
            }
        }
    }
}

@Composable
fun TPModzTheme(content: @Composable () -> Unit) {
    val cyberColors = darkColorScheme(
        primary = Color(0xFF00FF66),      // Neon Green
        secondary = Color(0xFF9D4EDD),    // Neon Purple
        background = Color(0xFF07050C),
        surface = Color(0xFF13111E),
        onPrimary = Color.Black,
        onSecondary = Color.White
    )

    MaterialTheme(
        colorScheme = cyberColors,
        typography = Typography(),
        content = content
    )
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, activity: ComponentActivity) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Persistent State Tracker flows
    val isVpnActive by VpnStateTracker.isVpnActive.collectAsState()
    val selectedPackages by VpnStateTracker.selectedPackages.collectAsState()
    val fakePingMs by VpnStateTracker.fakePingValue.collectAsState()
    val isFakePing by VpnStateTracker.isFakePingEnabled.collectAsState()
    val lagMode by VpnStateTracker.lagMode.collectAsState()
    val isDisconnecting by VpnStateTracker.isDisconnecting.collectAsState()
    val disconnectRemainingMillis by VpnStateTracker.disconnectMillisLeft.collectAsState()
    val isOverlayActive by VpnStateTracker.isOverlayActive.collectAsState()
    val cutSeconds by VpnStateTracker.cutSeconds.collectAsState()
    val cutMillis by VpnStateTracker.cutMillis.collectAsState()
    val isInfinite by VpnStateTracker.isInfinite.collectAsState()

    // System Permissions checking states
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isNotificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    // App state loaded list
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var appSearchQuery by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }

    // Launcher for VPN authorization permission
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(context)
        } else {
            Toast.makeText(context, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher for Overlay settings screen
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isOverlayGranted = Settings.canDrawOverlays(context)
        if (isOverlayGranted) {
            startOverlayService(context)
        }
    }

    // Launcher for Notification permission drawer
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isNotificationGranted = granted
    }

    // Load/scan launcher packages once on startup
    LaunchedEffect(Unit) {
        isScanning = true
        coroutineScope.launch(Dispatchers.IO) {
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchables = packageManager.queryIntentActivities(intent, 0)
            val appsList = launchables.map { info ->
                val packageName = info.activityInfo.packageName
                val name = info.loadLabel(packageManager).toString()
                val icon = info.loadIcon(packageManager)
                AppInfo(packageName, name, icon)
            }.sortedBy { it.name }

            installedApps = appsList
            isScanning = false
        }
        
        // Dynamic lifecycle app overlay checks
        while (true) {
            delay(1500)
            isOverlayGranted = Settings.canDrawOverlays(context)
        }
    }

    // Dynamic automatic start when permissions are granted and state is ON
    LaunchedEffect(isOverlayActive, isOverlayGranted) {
        if (isOverlayActive && isOverlayGranted) {
            startOverlayService(context)
        }
    }

    // Functions to trigger Services
    fun launchVpnBooster() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(context, "Vui lòng chọn ít nhất 1 ứng dụng/game", Toast.LENGTH_SHORT).show()
            return
        }

        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService(context)
        }
    }

    fun disableVpnBooster() {
        val stopIntent = Intent(context, TpVpnService::class.java).apply {
            action = TpVpnService.ACTION_STOP
        }
        context.stopService(stopIntent)
        VpnStateTracker.setVpnActive(false)
    }

    // Body container
    LazyColumn(
        modifier = modifier
            .background(Color(0xFF07050C))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Space header padding
        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 1. BRAND HEADER & STATUS
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FreedomTitle()
                Text(
                    text = "PLAYPING GAME NETWORK ENGINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFF00FF66) // Neon Green
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Connection status lights
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusBadge(
                        label = "VPN BOOSTER",
                        active = isVpnActive,
                        activeColor = Color(0xFF00FF66),
                        inactiveColor = Color(0xFFFF2E56)
                    )
                    StatusBadge(
                        label = "OVERLAY BTN",
                        active = isOverlayActive,
                        activeColor = Color(0xFF9146FF),
                        inactiveColor = Color.DarkGray
                    )
                }
            }
        }

        // 2. PRIVILEGES / PERMISSION DRAWER (If any missing)
        val showPermissionBox = !isOverlayGranted || !isNotificationGranted
        if (showPermissionBox) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF9146FF), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131022)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "YÊU CẦU QUYỀN HỆ THỐNG",
                            color = Color(0xFF00FF66),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "TPMODZ cần quyền lớp phủ để hiển thị controller khi đang trong game, và quyền thông báo cho VPN Service chạy mượt.",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (!isOverlayGranted) {
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF)),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Text("Cấp Quyền Lớp Phủ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (!isNotificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Button(
                                    onClick = {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B1B32)),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Text("Cấp Quyền Báo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. MASTER CONTROL CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1F1D36), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF100E1C)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Big Master Button
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .shadow(24.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        if (isVpnActive) Color(0xFF003817) else Color(0xFF291047),
                                        Color(0xFF0E0B1A)
                                    )
                                )
                            )
                            .border(
                                3.dp,
                                if (isVpnActive) Color(0xFF00FF66) else Color(0xFF9146FF),
                                CircleShape
                            )
                            .clickable {
                                if (isVpnActive) disableVpnBooster() else launchVpnBooster()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (isVpnActive) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                contentDescription = "Toggle",
                                tint = if (isVpnActive) Color(0xFF00FF66) else Color(0xFF9146FF),
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = if (isVpnActive) "BOOSTING" else "START",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // OVERLAY SWITCH
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF161327))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "HIỂN THỊ NÚT NỔI OVERLAY",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Switch(
                            checked = isOverlayActive,
                            onCheckedChange = { active ->
                                if (active) {
                                    if (isOverlayGranted) {
                                        startOverlayService(context)
                                    } else {
                                        Toast.makeText(context, "Hãy cấp quyền lớp phủ hệ thống trước", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    stopOverlayService(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF66),
                                checkedTrackColor = Color(0xFF003F17)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // FAKE PING SETTING
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "MÔ PHỎNG FAKE PING",
                            color = Color(0xFF9146FF),
                            size = 12,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "ON/OFF: ",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                            Switch(
                                checked = isFakePing,
                                onCheckedChange = {
                                    VpnStateTracker.setFakePingEnabled(context, it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00FF66),
                                    checkedTrackColor = Color(0xFF003F17)
                                ),
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = fakePingMs.toFloat(),
                            onValueChange = {
                                VpnStateTracker.setFakePingValue(context, it.toInt())
                            },
                            valueRange = 0f..999f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF9146FF),
                                activeTrackColor = Color(0xFF9146FF),
                                inactiveTrackColor = Color.DarkGray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${fakePingMs}ms",
                            color = Color(0xFF00FF66),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(54.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // MODE DIRECTION TABS
                    Text(
                        "CHẾ ĐỘ TÁC ĐỘNG (LAG DIRECTION)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF131024))
                            .border(1.dp, Color(0xFF1F1D36), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("Upload Only", "Download Only", "Both").forEach { mode ->
                            val isSelected = lagMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFF9146FF) else Color.Transparent)
                                    .clickable { VpnStateTracker.setLagMode(context, mode) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // TIME SELECTION FOR DISCONNECT
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "THỜI GIAN NGẮT MẠNG (CUT TIMER)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                VpnStateTracker.setIsInfinite(context, !isInfinite)
                            }
                        ) {
                            Checkbox(
                                checked = isInfinite,
                                onCheckedChange = { VpnStateTracker.setIsInfinite(context, it) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF9146FF),
                                    uncheckedColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Vô hạn",
                                color = if (isInfinite) Color.White else Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    if (!isInfinite) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            var secondsInput by remember(cutSeconds) { mutableStateOf(cutSeconds.toString()) }
                            var millisInput by remember(cutMillis) { mutableStateOf(cutMillis.toString()) }

                            OutlinedTextField(
                                value = secondsInput,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    secondsInput = filtered
                                    VpnStateTracker.setCutSeconds(context, filtered.toIntOrNull() ?: 0)
                                },
                                label = { Text("Số giây (s)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF9146FF),
                                    unfocusedBorderColor = Color(0xFF1F1D36),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color(0xFF9146FF),
                                    unfocusedLabelColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedTextField(
                                value = millisInput,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    millisInput = filtered
                                    VpnStateTracker.setCutMillis(context, filtered.toIntOrNull() ?: 0)
                                },
                                label = { Text("Số mili-giây (ms)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF9146FF),
                                    unfocusedBorderColor = Color(0xFF1F1D36),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color(0xFF9146FF),
                                    unfocusedLabelColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF131024))
                                .border(1.dp, Color(0xFF1F1D36), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Ngắt mạng vô hạn (Nhấn QUICK CUT để bật, nhấn lại để tắt)",
                                color = Color(0xFFFFCC00),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // QUICK DISCONNECT LAGSWITCH
                    Button(
                        onClick = {
                            if (isDisconnecting) {
                                VpnStateTracker.setDisconnecting(false, 0)
                            } else {
                                triggerDisconnectCut(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDisconnecting) Color(0xFFFF2E56) else Color(0xFF131024)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .border(
                                1.dp,
                                if (isDisconnecting) Color(0xFFFF2E56) else Color(0xFF9146FF),
                                RoundedCornerShape(10.dp)
                            ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isDisconnecting) {
                                    if (disconnectRemainingMillis == -1) "NGẮT MẠNG ĐANG BẬT [Vô hạn]" else "NGẮT MẠNG ĐANG BẬT [${"%.1f".format(disconnectRemainingMillis / 1000f)}g]"
                                } else {
                                    "KÍCH HOẠT LAG-SWITCH (QUICK CUT)"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                }
            }
        }

        // 4. PREPARED APPLICATION SELECTION SECTION
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                // Header section with action quick-selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ỨNG DỤNG BỊ TÁC ĐỘNG (${selectedPackages.size})",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Select All",
                            color = Color(0xFF00FF66),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val all = installedApps.map { it.packageName }
                                    VpnStateTracker.selectAllPackages(context, all)
                                }
                                .padding(4.dp)
                        )
                        Text(
                            "Clear All",
                            color = Color(0xFFFF2E56),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    VpnStateTracker.clearAllPackages(context)
                                }
                                .padding(4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                TextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Tìm game/ứng dụng...", fontSize = 12.sp, color = Color.Gray) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF1F1D36), RoundedCornerShape(8.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF100E1C),
                        unfocusedContainerColor = Color(0xFF100E1C),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                )
            }
        }

        // Search Filter result list of installed games/apps
        val filteredApps = installedApps.filter {
            it.name.contains(appSearchQuery, ignoreCase = true) ||
            it.packageName.contains(appSearchQuery, ignoreCase = true)
        }

        if (isScanning) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF9146FF))
                }
            }
        } else if (filteredApps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Không tìm thấy ứng dụng nào", color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else {
            items(filteredApps) { app ->
                val isChecked = app.packageName in selectedPackages
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF120F20))
                        .clickable {
                            VpnStateTracker.toggleSelectedPackage(context, app.packageName)
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App launcher icon
                    AppIconImage(
                        drawable = app.icon,
                        modifier = Modifier
                            .size(36.dp)
                            .shadow(2.dp, CircleShape)
                            .clip(RoundedCornerShape(6.dp))
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = app.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = {
                            VpnStateTracker.toggleSelectedPackage(context, app.packageName)
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00FF66),
                            uncheckedColor = Color.DarkGray
                        )
                    )
                }
            }
        }

        // Bottom spacing padding
        item { Spacer(modifier = Modifier.height(30.dp)) }
    }
}

@Composable
fun StatusBadge(label: String, active: Boolean, activeColor: Color, inactiveColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF131122))
            .border(1.dp, Color(0xFF1C1930), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else inactiveColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: ${if (active) "ACTIVE" else "OFF"}",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun AppIconImage(drawable: android.graphics.drawable.Drawable?, modifier: Modifier = Modifier) {
    if (drawable != null) {
        AndroidView(
            factory = { context ->
                android.widget.ImageView(context).apply {
                    setImageDrawable(drawable)
                }
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(Color.DarkGray, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("G", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Inline helper for Custom Label Size on Slider Texts
@Composable
fun Text(text: String, color: Color, size: Int, fontWeight: FontWeight, fontFamily: FontFamily) {
    Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontWeight = fontWeight,
        fontFamily = fontFamily
    )
}

private fun startVpnService(context: Context) {
    val serviceIntent = Intent(context, TpVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
    VpnStateTracker.setVpnActive(true)
}

private fun startOverlayService(context: Context) {
    val serviceIntent = Intent(context, FloatingOverlayService::class.java)
    context.startService(serviceIntent)
    VpnStateTracker.setOverlayActive(context, true)
}

private fun stopOverlayService(context: Context) {
    val serviceIntent = Intent(context, FloatingOverlayService::class.java)
    context.stopService(serviceIntent)
    VpnStateTracker.setOverlayActive(context, false)
}

private fun triggerDisconnectCut(context: Context) {
    if (VpnStateTracker.isDisconnecting.value) return
    val totalMs = if (VpnStateTracker.isInfinite.value) -1 else (VpnStateTracker.cutSeconds.value * 1000 + VpnStateTracker.cutMillis.value)
    VpnStateTracker.setDisconnecting(true, totalMs)
    CoroutineScope(Dispatchers.Main).launch {
        while ((VpnStateTracker.disconnectMillisLeft.value > 0 || VpnStateTracker.disconnectMillisLeft.value == -1) && VpnStateTracker.isDisconnecting.value) {
            delay(100)
            VpnStateTracker.tickDisconnectMillis(100)
        }
    }
}

@Composable
fun FreedomTitle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FreedomLetter('T', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
        FreedomLetter('P', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
        FreedomLetter('M', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
        FreedomLetter('O', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
        FreedomLetter('D', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
        FreedomLetter('Z', color = Color(0xFF9146FF), glowColor = Color(0xFF00FF66))
    }
}

@Composable
fun FreedomLetter(char: Char, color: Color, glowColor: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(width = 32.dp, height = 44.dp)
    ) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            when (char) {
                'T' -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h * 0.22f)
                    lineTo(w * 0.62f, h * 0.22f)
                    lineTo(w * 0.62f, h * 0.45f)
                    moveTo(w * 0.62f, h * 0.52f)
                    lineTo(w * 0.62f, h)
                    lineTo(w * 0.38f, h)
                    lineTo(w * 0.38f, h * 0.52f)
                    moveTo(w * 0.38f, h * 0.45f)
                    lineTo(w * 0.38f, h * 0.22f)
                    lineTo(0f, h * 0.22f)
                    close()
                }
                'P' -> {
                    moveTo(0f, 0f)
                    lineTo(w * 0.75f, 0f)
                    lineTo(w, h * 0.22f)
                    lineTo(w, h * 0.55f)
                    lineTo(w * 0.75f, h * 0.65f)
                    lineTo(w * 0.28f, h * 0.65f)
                    lineTo(w * 0.28f, h * 0.45f)
                    moveTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.28f, h)
                    lineTo(0f, h)
                    lineTo(0f, h * 0.52f)
                    moveTo(0f, h * 0.45f)
                    lineTo(0f, 0f)
                    close()

                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.68f, h * 0.18f)
                    lineTo(w * 0.75f, h * 0.24f)
                    lineTo(w * 0.75f, h * 0.44f)
                    lineTo(w * 0.68f, h * 0.49f)
                    lineTo(w * 0.28f, h * 0.49f)
                    close()
                }
                'M' -> {
                    moveTo(0f, 0f)
                    lineTo(w * 0.24f, 0f)
                    lineTo(w * 0.24f, h)
                    lineTo(0f, h)
                    close()

                    moveTo(w * 0.76f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(w * 0.76f, h)
                    close()

                    moveTo(w * 0.24f, 0f)
                    lineTo(w * 0.5f, h * 0.68f)
                    lineTo(w * 0.76f, 0f)
                    lineTo(w * 0.6f, 0f)
                    lineTo(w * 0.5f, h * 0.38f)
                    lineTo(w * 0.4f, 0f)
                    close()
                }
                'O' -> {
                    moveTo(w * 0.22f, 0f)
                    lineTo(w * 0.78f, 0f)
                    lineTo(w, h * 0.22f)
                    lineTo(w, h * 0.45f)
                    moveTo(w, h * 0.55f)
                    lineTo(w, h * 0.78f)
                    lineTo(w * 0.78f, h)
                    lineTo(w * 0.22f, h)
                    lineTo(0f, h * 0.78f)
                    lineTo(0f, h * 0.55f)
                    moveTo(0f, h * 0.45f)
                    lineTo(0f, h * 0.22f)
                    close()

                    moveTo(w * 0.26f, h * 0.18f)
                    lineTo(w * 0.74f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.24f)
                    lineTo(w * 0.82f, h * 0.45f)
                    moveTo(w * 0.82f, h * 0.55f)
                    lineTo(w * 0.82f, h * 0.76f)
                    lineTo(w * 0.74f, h * 0.82f)
                    lineTo(w * 0.26f, h * 0.82f)
                    lineTo(w * 0.18f, h * 0.76f)
                    lineTo(w * 0.18f, h * 0.55f)
                    moveTo(w * 0.18f, h * 0.45f)
                    lineTo(w * 0.18f, h * 0.24f)
                    close()
                }
                'D' -> {
                    moveTo(0f, 0f)
                    lineTo(w * 0.7f, 0f)
                    lineTo(w, h * 0.3f)
                    lineTo(w, h * 0.7f)
                    lineTo(w * 0.7f, h)
                    lineTo(0f, h)
                    close()

                    moveTo(w * 0.28f, h * 0.2f)
                    lineTo(w * 0.64f, h * 0.2f)
                    lineTo(w * 0.76f, h * 0.32f)
                    lineTo(w * 0.76f, h * 0.68f)
                    lineTo(w * 0.64f, h * 0.8f)
                    lineTo(w * 0.28f, h * 0.8f)
                    close()
                }
                'Z' -> {
                    moveTo(0f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h * 0.2f)
                    lineTo(w * 0.32f, h * 0.8f)
                    lineTo(w, h * 0.8f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    lineTo(0f, h * 0.8f)
                    lineTo(w * 0.68f, h * 0.2f)
                    lineTo(0f, h * 0.2f)
                    close()
                }
            }
        }

        // 1. Shadow glow
        drawPath(
            path = path,
            color = glowColor.copy(alpha = 0.2f),
            style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 2. Base Fill Color
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(color, color.copy(alpha = 0.85f))
            )
        )

        // 3. Crisp Foreground Stroke Outline
        drawPath(
            path = path,
            color = glowColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
