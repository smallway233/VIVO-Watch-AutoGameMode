package top.smallway.gamewatch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import top.smallway.gamewatch.ui.theme.GameWatchTheme

import android.util.Log

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 作者：Smallway
// 日期：2026-01-04
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "onReceive: action=${intent?.action}")
            if (intent?.action == "top.smallway.gamewatch.ACTION_STOP_GAME") {
                // Ensure UI operations are on main thread, though onReceive usually is
                runOnUiThread {
                    Log.d(TAG, "Executing moveTaskToBack(true)")
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Activity entered foreground")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Activity entered background or stopped")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created")
        enableEdgeToEdge()

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 检查自启动配置
        val prefs = getSharedPreferences("GameWatchPrefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_service", true)

        // 启动服务
        if (autoStart) {
            val serviceIntent = Intent(this, GamingSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        // 注册广播接收器
        val filter = IntentFilter("top.smallway.gamewatch.ACTION_STOP_GAME")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }

        setContent {
            GameWatchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isFromService = intent.getBooleanExtra("EXTRA_FROM_SERVICE", false)
                    // Check if game is running. If running, do not auto-hide even if manual launch.
                    val isGameRunning = GamingSyncService.isGameRunning
                    MainScreen(
                        autoHide = !isFromService && !isGameRunning,
                        onHideRequested = {
                            Log.d(TAG, "Auto-hiding activity to background")
                            moveTaskToBack(true)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
    }
}

@Composable
fun MainScreen(autoHide: Boolean, onHideRequested: () -> Unit) {
    val context = LocalContext.current
    
    // State
    var currentGameName by remember { mutableStateOf(GamingSyncService.currentGameName) }
    var gameStartTime by remember { mutableStateOf(GamingSyncService.gameStartTime) }
    var isGameRunning by remember { mutableStateOf(GamingSyncService.isGameRunning) }
    var isServiceRunning by remember { mutableStateOf(GamingSyncService.isServiceRunning) }
    var durationText by remember { mutableStateOf("00:00:00") }
    var historyList by remember { mutableStateOf(loadHistory(context)) }
    val prefs = context.getSharedPreferences("GameWatchPrefs", Context.MODE_PRIVATE)

    // Broadcast Receiver for Real-time updates
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "top.smallway.gamewatch.ACTION_GAME_START" -> {
                        currentGameName = intent.getStringExtra("gameName") ?: ""
                        gameStartTime = intent.getLongExtra("startTime", 0L)
                        isGameRunning = true
                        isServiceRunning = true
                    }
                    "top.smallway.gamewatch.ACTION_STOP_GAME" -> {
                        isGameRunning = false
                        // Reload history
                        historyList = loadHistory(context!!)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("top.smallway.gamewatch.ACTION_GAME_START")
            addAction("top.smallway.gamewatch.ACTION_STOP_GAME")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Timer Logic
    LaunchedEffect(isGameRunning, gameStartTime) {
        if (isGameRunning && gameStartTime > 0L) {
            while (true) {
                val duration = System.currentTimeMillis() - gameStartTime
                durationText = formatDuration(duration)
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    // Request Notification Permission for Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted && autoHide) {
                 onHideRequested()
            }
        }
        
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                if (autoHide) {
                    kotlinx.coroutines.delay(1000)
                    if (!GamingSyncService.isGameRunning) {
                        onHideRequested()
                    }
                }
            }
        }
    } else {
        LaunchedEffect(Unit) {
            if (autoHide) onHideRequested()
        }
    }
    
    // Check for Overlay Permission (Display over other apps)
    val showOverlayPermissionButton = remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            showOverlayPermissionButton.value = true
        }
    }

    val configuration = LocalConfiguration.current
    if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isGameRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("正在游玩", color = Color.Gray, fontSize = 20.sp)
                            Text(currentGameName, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(32.dp))
                        Box(modifier = Modifier.width(2.dp).height(80.dp).background(Color.DarkGray))
                        Spacer(modifier = Modifier.width(32.dp))
                        Text(durationText, color = Color(0xFF00FF00), fontSize = 80.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    if (isServiceRunning) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("等待游戏连接...", color = Color.Gray, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("监听端口: 8888", color = Color.DarkGray, fontSize = 16.sp)
                        }
                    } else {
                        Text("服务未启动", color = Color.Red, fontSize = 24.sp)
                    }
                }
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color(0xFF1A1A1A),
            contentWindowInsets = WindowInsets.systemBars
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PC-Android 联动系统",
                color = Color(0xFF00FF00), // Matrix Green
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isGameRunning) {
                        Text("正在游玩", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(currentGameName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(durationText, color = Color(0xFF00FF00), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    } else {
                        if (isServiceRunning) {
                            Text("等待游戏连接...", color = Color.Gray, fontSize = 16.sp)
                            Text("监听端口: 8888", color = Color.DarkGray, fontSize = 12.sp)
                        } else {
                            Text("服务未启动", color = Color.Red, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    // Re-enable auto-start and start service
                                    prefs.edit().putBoolean("auto_start_service", true).apply()
                                    val serviceIntent = Intent(context, GamingSyncService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                    isServiceRunning = true
                                    GamingSyncTileService.requestListeningState(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF00))
                            ) {
                                Text("启动服务", color = Color.Black)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // History Section
            Text("历史记录", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(historyList) { session ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(session.name, color = Color.White, fontSize = 16.sp)
                                Text(formatTime(session.startTime), color = Color.Gray, fontSize = 12.sp)
                            }
                            Text(formatDuration(session.duration), color = Color(0xFF00FF00), fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (showOverlayPermissionButton.value) {
                Button(
                    onClick = { 
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                             val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                             context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开启悬浮窗权限 (必开)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { openSmallwayPermissions(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开启软件后台运行权限")
            }
        }
    }
    }
}

fun openSmallwayPermissions(context: Context) {
    try {
        val intent = Intent()
        intent.component = ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            )
            context.startActivity(intent)
        } catch (e2: Exception) {
             // Fallback to settings
            try {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                context.startActivity(intent)
            } catch (e3: Exception) {
                // Ignore
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    GameWatchTheme {
        MainScreen(autoHide = false, onHideRequested = {})
    }
}
