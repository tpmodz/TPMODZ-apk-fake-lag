package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.FloatingOverlayService
import com.example.service.TpVpnService
import com.example.state.VpnStateTracker
import kotlinx.coroutines.*

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
                MainScreen(this)
            }
        }
    }
}

@Composable
fun TPModzTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun MainScreen(activity: ComponentActivity) {

    val context = activity
    val scope = rememberCoroutineScope()

    val isVpnActive by VpnStateTracker.isVpnActive.collectAsState()
    val selectedPackages by VpnStateTracker.selectedPackages.collectAsState()
    val fakePingMs by VpnStateTracker.fakePingValue.collectAsState()
    val isFakePing by VpnStateTracker.isFakePingEnabled.collectAsState()
    val lagMode by VpnStateTracker.lagMode.collectAsState()
    val isDisconnecting by VpnStateTracker.isDisconnecting.collectAsState()
    val isOverlayActive by VpnStateTracker.isOverlayActive.collectAsState()
    val cutSeconds by VpnStateTracker.cutSeconds.collectAsState()
    val cutMillis by VpnStateTracker.cutMillis.collectAsState()
    val isInfinite by VpnStateTracker.isInfinite.collectAsState()
    val disconnectMillisLeft by VpnStateTracker.disconnectMillisLeft.collectAsState()
    val livePing by VpnStateTracker.currentLivePing.collectAsState()

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var search by remember { mutableStateOf("") }

    val setupCompleted by VpnStateTracker.setupCompleted.collectAsState()
    var currentScreen by remember { mutableStateOf(if (VpnStateTracker.setupCompleted.value) "main" else "selection") }

    val vpnLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startVpn(context)
        }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appsList = installedApps.mapNotNull { appInfo ->
                try {
                    val pkgName = appInfo.packageName
                    val name = appInfo.loadLabel(pm).toString()
                    val icon = appInfo.loadIcon(pm)
                    AppInfo(pkgName, name, icon)
                } catch (e: Exception) {
                    null
                }
            }.distinctBy { it.packageName }.sortedBy { it.name }

            withContext(Dispatchers.Main) {
                apps = appsList
            }
        }
    }

    if (currentScreen == "selection") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0612))
                .padding(16.dp)
        ) {
            Text(
                "CHỌN ỨNG DỤNG",
                color = Color.Green,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                "Chọn các ứng dụng cần can thiệp ping/VPN:",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Tìm kiếm ứng dụng...") },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E152D),
                    unfocusedContainerColor = Color(0xFF1E152D),
                    focusedIndicatorColor = Color.Green,
                    unfocusedIndicatorColor = Color.Gray,
                    focusedPlaceholderColor = Color.Gray,
                    unfocusedPlaceholderColor = Color.Gray
                )
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(apps.filter {
                    it.name.contains(search, true) || it.packageName.contains(search, true)
                }) { app ->
                    val checked = app.packageName in selectedPackages

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { VpnStateTracker.toggleApp(app.packageName) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIconImage(
                            drawable = app.icon,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )

                        Spacer(Modifier.width(10.dp))

                        Column(Modifier.weight(1f)) {
                            Text(app.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                        }

                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                VpnStateTracker.toggleApp(app.packageName)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color.Green,
                                uncheckedColor = Color.Gray,
                                checkmarkColor = Color.Black
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    VpnStateTracker.setSetupCompleted(context, true)
                    currentScreen = "main"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Green,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("TIẾP TỤC", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    } else {
        Scaffold(
            containerColor = Color(0xFF0C071C),
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isOverlayActive) {
                            context.stopService(Intent(context, FloatingOverlayService::class.java))
                            VpnStateTracker.setOverlayActive(context, false)
                            Toast.makeText(context, "Đã tắt nút nổi", Toast.LENGTH_SHORT).show()
                        } else {
                            startOverlay(context)
                            Toast.makeText(context, "Kích hoạt nút nổi thành công", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = if (isOverlayActive) Color(0xFFFF2551) else Color(0xFF9146FF),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isOverlayActive) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "Toggle Floating Widget",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isOverlayActive) "TẮT NÚT NỔI" else "BẬT NÚT NỔI",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color(0xFF0C071C))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // HEADER BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "TPMODZ PANEL",
                        color = Color.Green,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Bảng điều khiển chuyên nghiệp",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = { currentScreen = "selection" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF231B3A),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Select Apps",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Chọn lại App", fontSize = 11.sp)
                }
            }

            // MAIN CONTAINER (SCROLLABLE PANEL)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // STATUS & VPN CARD
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF140E24)),
                    border = BorderStroke(1.dp, if (isVpnActive) Color.Green else Color.Gray),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isVpnActive) Color.Green else Color.Red)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isVpnActive) "VPN: ĐANG HOẠT ĐỘNG" else "VPN: ĐANG TẮT",
                                    color = if (isVpnActive) Color.Green else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            // Digital Ping display
                            Text(
                                if (isDisconnecting) "PING: 999ms (CUT)" else "PING HIỆN TẠI: ${livePing}ms",
                                color = if (isDisconnecting) Color.Red else if (isFakePing) Color(0xFF9146FF) else Color.Green,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                val intent = VpnService.prepare(context)
                                if (intent != null) vpnLauncher.launch(intent)
                                else startVpn(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isVpnActive) Color(0xFFFF2551) else Color.Green,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isVpnActive) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = "Toggle VPN",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isVpnActive) "TẮT MẠNG VPN (STOP)" else "KÍCH HOẠT VPN (START)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // LAG DIRECTION MODE (Up / Down / Both) - "bât tắt up down đồ á"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF140E24)),
                    border = BorderStroke(1.dp, Color(0xFF2C194D)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "CHẾ ĐỘ LÀM LAG (UP / DOWN)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Chọn hướng mạng để can thiệp làm trễ ping:",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Down", "Up", "Both").forEach { mode ->
                                val active = (lagMode == mode)
                                val label = when (mode) {
                                    "Down" -> "Chỉ DOWN"
                                    "Up" -> "Chỉ UP"
                                    else -> "CẢ HAI"
                                }

                                Button(
                                    onClick = { VpnStateTracker.setLagMode(context, mode) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (active) Color(0xFF9146FF) else Color(0xFF21153A),
                                        contentColor = if (active) Color.White else Color.LightGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // FAKE PING Lg CONFIG
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF140E24)),
                    border = BorderStroke(1.dp, Color(0xFF2C194D)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "LAG GIẢ LẬP (FAKE PING)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Cấu hình độ trễ ping tuỳ ý",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = isFakePing,
                                onCheckedChange = { VpnStateTracker.setFakePingEnabled(context, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Green,
                                    checkedTrackColor = Color(0xFF1B4222),
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color.DarkGray
                                )
                            )
                        }

                        if (isFakePing) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Độ trễ ping:", color = Color.LightGray, fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF9146FF))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "${fakePingMs} ms",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Slider(
                                value = fakePingMs.toFloat(),
                                onValueChange = { VpnStateTracker.setFakePingValue(context, it.toInt()) },
                                valueRange = 10f..999f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Green,
                                    activeTrackColor = Color.Green,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )

                            // Quick preset chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(120, 250, 450, 700, 999).forEach { preset ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (fakePingMs == preset) Color.Green else Color(0xFF21153A))
                                            .clickable { VpnStateTracker.setFakePingValue(context, preset) }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${preset}",
                                            color = if (fakePingMs == preset) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // QUICK DISCONNECT (NGẮT MẠNG TỨC THỜI)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF140E24)),
                    border = BorderStroke(1.dp, Color(0xFF2C194D)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "NGẮT MẠNG TỨC THỜI (QUICK CUT)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )

                        // Duration toggle (Infinite vs Custom)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Thời gian cut mạng:", color = Color.LightGray, fontSize = 12.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isInfinite) Color.Green else Color(0xFF21153A))
                                        .clickable { VpnStateTracker.setIsInfinite(context, true) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Vô hạn",
                                        color = if (isInfinite) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isInfinite) Color.Green else Color(0xFF21153A))
                                        .clickable { VpnStateTracker.setIsInfinite(context, false) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "${cutSeconds}s ${cutMillis}ms",
                                        color = if (!isInfinite) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Custom sliders if not infinite
                        if (!isInfinite) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Đặt Giây: ${cutSeconds}s", color = Color.Gray, fontSize = 11.sp)
                                }
                                Slider(
                                    value = cutSeconds.toFloat(),
                                    onValueChange = { VpnStateTracker.setCutSeconds(context, it.toInt()) },
                                    valueRange = 1f..30f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Green,
                                        activeTrackColor = Color.Green,
                                        inactiveTrackColor = Color.DarkGray
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Đặt Millis: ${cutMillis}ms", color = Color.Gray, fontSize = 11.sp)
                                }
                                Slider(
                                    value = cutMillis.toFloat(),
                                    onValueChange = { VpnStateTracker.setCutMillis(context, it.toInt()) },
                                    valueRange = 0f..900f,
                                    steps = 8,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Green,
                                        activeTrackColor = Color.Green,
                                        inactiveTrackColor = Color.DarkGray
                                    )
                                )
                            }
                        }

                        // Big action trigger
                        Button(
                            onClick = {
                                if (VpnStateTracker.isDisconnecting.value) {
                                    VpnStateTracker.setDisconnecting(false, 0)
                                } else {
                                    val totalMs = if (isInfinite) -1 else (cutSeconds * 1000 + cutMillis)
                                    VpnStateTracker.setDisconnecting(true, totalMs)
                                    scope.launch {
                                        while ((VpnStateTracker.disconnectMillisLeft.value > 0 || VpnStateTracker.disconnectMillisLeft.value == -1) && VpnStateTracker.isDisconnecting.value) {
                                            delay(100)
                                            VpnStateTracker.tickDisconnectMillis(100)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDisconnecting) Color(0xFFFF2551) else Color(0xFF9146FF),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isDisconnecting) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = "Quick Cut action",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isDisconnecting) {
                                    if (disconnectMillisLeft == -1) "ĐANG NGẮT MẠNG [Vô hạn]" else "ĐANG NGẮT MẠNG [${disconnectMillisLeft / 1000}.${(disconnectMillisLeft % 1000) / 100}s]"
                                } else {
                                    "KÍCH HOẠT NGẮT MẠNG (QUICK CUT)"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // FLOATING OVERLAY & GUARDED APPS INFO
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF140E24)),
                    border = BorderStroke(1.dp, Color(0xFF2C194D)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "BẢNG ĐIỀU KHIỂN NỔI (OVERLAY)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Hiển thị nút điều khiển nổi khi chơi game",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }

                            Switch(
                                checked = isOverlayActive,
                                onCheckedChange = {
                                    if (it) {
                                        startOverlay(context)
                                    } else {
                                        context.stopService(Intent(context, FloatingOverlayService::class.java))
                                        VpnStateTracker.setOverlayActive(context, false)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Green,
                                    checkedTrackColor = Color(0xFF1B4222),
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color.DarkGray
                                )
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF231B3A)))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "ỨNG DỤNG ĐÃ CHỌN",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Đang bảo vệ: ${selectedPackages.size} ứng dụng",
                                    color = Color.Green,
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = { currentScreen = "selection" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF21153A),
                                    contentColor = Color.Green
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Text("Xem/Sửa", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun AppIconImage(drawable: android.graphics.drawable.Drawable?, modifier: Modifier = Modifier) {
    if (drawable != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
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
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/* ===================== FUNCTIONS ===================== */

fun startVpn(context: Context) {
    val intent = Intent(context, TpVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    VpnStateTracker.setVpnActive(true)
}

fun startOverlay(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }

    context.startService(Intent(context, FloatingOverlayService::class.java))
    VpnStateTracker.setOverlayActive(context, true)
}