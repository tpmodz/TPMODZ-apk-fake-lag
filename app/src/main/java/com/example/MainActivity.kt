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

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var search by remember { mutableStateOf("") }

    val vpnLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startVpn(context)
        }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        apps = pm.queryIntentActivities(intent, 0).map {
            AppInfo(
                it.activityInfo.packageName,
                it.loadLabel(pm).toString(),
                it.loadIcon(pm)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0612))
            .padding(16.dp)
    ) {

        Text("TPMODZ PANEL", color = Color.Green, fontSize = 20.sp)

        Spacer(Modifier.height(10.dp))

        Button(onClick = {
            val intent = VpnService.prepare(context)
            if (intent != null) vpnLauncher.launch(intent)
            else startVpn(context)
        }) {
            Text(if (isVpnActive) "VPN ON" else "START VPN")
        }

        Spacer(Modifier.height(10.dp))

        TextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps") }
        )

        Spacer(Modifier.height(10.dp))

        LazyColumn {
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

                    Icon(Icons.Default.Android, null, tint = Color.White)

                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Color.White)
                        Text(app.packageName, color = Color.Gray, fontSize = 10.sp)
                    }

                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            VpnStateTracker.toggleApp(app.packageName)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(onClick = {
            startOverlay(context)
        }) {
            Text(if (isOverlayActive) "Overlay ON" else "Start Overlay")
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