package top.smallway.gamewatch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

// 作者：Smallway
// 日期：2026-01-04
class GamingSyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private var lastHeartbeatTime = 0L
    private val HEARTBEAT_TIMEOUT = 15000L // 15 seconds

    companion object {
        private const val TAG = "GamingSyncService"
        private const val PORT = 8888
        private const val SERVICE_TYPE = "_gamingsync._udp." // local. is implied in some contexts but usually passed as _type._proto.
        private const val SERVICE_NAME = "SmallwayGameSync"
        const val ACTION_EXIT = "top.smallway.gamewatch.ACTION_EXIT"
        // Public flag to track game status, accessible by MainActivity
        var isGameRunning = false
        var currentGameName = ""
        var gameStartTime = 0L
        
        // Service running status
        var isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            Log.d(TAG, "Exit action received. Stopping service and disabling auto-start.")
            // Disable auto-start
            val prefs = getSharedPreferences("GameWatchPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_start_service", false).apply()
            
            // Stop service
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.d(TAG, "GamingSyncService created")
        startForegroundServiceNotification()
        startUdpServer()
        registerNsdService()
        GamingSyncTileService.requestListeningState(this)
    }

    private fun startForegroundServiceNotification() {
        updateNotification("未连接至PC")
    }

    private fun updateNotification(content: String, isHighPriority: Boolean = false) {
        val channelId = if (isHighPriority) "GamingSyncChannel_High" else "GamingSyncChannel_Low"
        val channelName = if (isHighPriority) "Gaming Mode Event" else "Gaming Sync Service"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val importance = if (isHighPriority) 
                android.app.NotificationManager.IMPORTANCE_HIGH 
            else 
                android.app.NotificationManager.IMPORTANCE_LOW
            
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                importance
            ).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = android.app.Notification.Builder(this, channelId)
            .setContentTitle("电竞模式同步中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)

        // Add Exit Action
        val exitIntent = Intent(this, GamingSyncService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = android.app.PendingIntent.getService(
            this,
            0,
            exitIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Using a generic icon for the action since we don't have custom resources handy
        // In modern Android, action icons are often ignored in the expanded view but required for the API
        val actionIcon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val action = android.app.Notification.Action.Builder(actionIcon, "关闭服务", exitPendingIntent).build()
        builder.addAction(action)

        if (isHighPriority) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(pendingIntent, true)
            builder.setCategory(android.app.Notification.CATEGORY_ALARM)
        }

        val notification = builder.build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d(TAG, "GamingSyncService destroyed")
        // 重置状态
        isGameRunning = false
        currentGameName = ""
        gameStartTime = 0L
        
        stopUdpServer()
        unregisterNsdService()
        serviceScope.cancel()
        GamingSyncTileService.requestListeningState(this)
    }

    private fun registerNsdService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun unregisterNsdService() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NSD service", e)
        }
    }

    private fun startUdpServer() {
        isRunning = true
        
        // Heartbeat Monitor
        serviceScope.launch {
            while (isRunning) {
                kotlinx.coroutines.delay(5000)
                if (lastHeartbeatTime > 0 && System.currentTimeMillis() - lastHeartbeatTime > HEARTBEAT_TIMEOUT) {
                    Log.w(TAG, "Heartbeat timeout - Connection lost")
                    lastHeartbeatTime = 0 // Reset to avoid repeated timeouts
                    handleDisconnectCommand()
                }
            }
        }

        serviceScope.launch {
            try {
                // Binding to 0.0.0.0 to listen on all interfaces
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "UDP Server listening on port $PORT")

                while (isRunning) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        // Log.d(TAG, "Received message: $message")
                        handleMessage(message, packet)
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Error receiving packet", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting UDP server", e)
            } finally {
                socket?.close()
            }
        }
    }

    private fun stopUdpServer() {
        isRunning = false
        socket?.close()
    }

    private fun saveGameHistory(gameName: String, startTime: Long, endTime: Long) {
        val prefs = getSharedPreferences("game_history", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("history_list", "[]")
        try {
            val jsonArray = org.json.JSONArray(historyJson)
            val session = JSONObject().apply {
                put("name", gameName)
                put("startTime", startTime)
                put("endTime", endTime)
                put("duration", endTime - startTime)
            }
            // Add to beginning
            val newArray = org.json.JSONArray()
            newArray.put(session)
            for (i in 0 until jsonArray.length()) {
                newArray.put(jsonArray.get(i))
            }
            prefs.edit().putString("history_list", newArray.toString()).apply()
            Log.d(TAG, "Saved game history: $gameName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history", e)
        }
    }

    private fun handleMessage(message: String, packet: DatagramPacket) {
        try {
            // Update liveness
            lastHeartbeatTime = System.currentTimeMillis()

            if (message.startsWith("{")) {
                val json = JSONObject(message)
                val cmd = json.optString("cmd")
                
                if (cmd != "HEARTBEAT") {
                    Log.d(TAG, "Received command: $cmd")
                }

                when (cmd) {
                    "HEARTBEAT" -> {
                        sendHeartbeatAck(packet.address, packet.port)
                    }
                    "START" -> {
                        val gameName = json.optString("game", "Unknown Game")
                        val time = json.optLong("time", System.currentTimeMillis())
                        handleStartCommand(gameName, time)
                    }
                    "STOP" -> {
                        val gameName = json.optString("game", currentGameName)
                        val time = json.optLong("time", gameStartTime)
                        handleStopCommand(gameName, time)
                    }
                    "CONNECT" -> handleConnectCommand()
                    "DISCONNECT" -> handleDisconnectCommand()
                }
            } else {
                // Backward compatibility
                Log.d(TAG, "Received legacy message: $message")
                when (message) {
                    "CONNECT" -> handleConnectCommand()
                    "DISCONNECT" -> handleDisconnectCommand()
                    "START" -> handleStartCommand("Unknown Game", System.currentTimeMillis())
                    "STOP" -> handleStopCommand(currentGameName, gameStartTime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun sendHeartbeatAck(address: java.net.InetAddress, port: Int) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("cmd", "HEARTBEAT_ACK")
                }
                val data = json.toString().toByteArray()
                val packet = DatagramPacket(data, data.size, address, port)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HEARTBEAT_ACK", e)
            }
        }
    }

    private fun handleConnectCommand() {
        Log.d(TAG, "Processing CONNECT command")
        updateNotification("已连接至PC")
        broadcastStopGame()
    }

    private fun handleDisconnectCommand() {
        Log.d(TAG, "Processing DISCONNECT command")
        updateNotification("未连接至PC")
        broadcastStopGame()
    }

    private fun broadcastStopGame() {
        val intent = Intent("top.smallway.gamewatch.ACTION_STOP_GAME")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun handleStartCommand(gameName: String, startTime: Long) {
        Log.d(TAG, "Processing START command: $gameName")
        GamingSyncService.isGameRunning = true
        GamingSyncService.currentGameName = gameName
        GamingSyncService.gameStartTime = startTime

        updateNotification("正在游玩: $gameName", isHighPriority = true)
        
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("EXTRA_FROM_SERVICE", true)
            // Pass data
            putExtra("gameName", gameName)
            putExtra("startTime", startTime)
        }
        
        // Broadcast for UI update if already visible
        val broadcastIntent = Intent("top.smallway.gamewatch.ACTION_GAME_START")
        broadcastIntent.putExtra("gameName", gameName)
        broadcastIntent.putExtra("startTime", startTime)
        broadcastIntent.setPackage(packageName)
        sendBroadcast(broadcastIntent)

        // Try multiple methods to launch activity
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Method 1 (startActivity) failed", e)
        }

        try {
             val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                1001,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
        } catch (e: Exception) {
            Log.e(TAG, "Method 2 (PendingIntent.send) failed", e)
        }
    }

    private fun handleStopCommand(gameName: String, startTime: Long) {
        Log.d(TAG, "Processing STOP command")
        
        // Save history if we had a valid session
        if (isGameRunning && gameStartTime > 0) {
             saveGameHistory(currentGameName, gameStartTime, System.currentTimeMillis())
        }

        GamingSyncService.isGameRunning = false
        updateNotification("已连接至PC")
        broadcastStopGame()
    }
}
