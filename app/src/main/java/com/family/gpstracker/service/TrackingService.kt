package com.family.gpstracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.family.gpstracker.R
import com.family.gpstracker.data.local.AppDatabase
import com.family.gpstracker.data.local.LocationEntity
import com.family.gpstracker.receiver.NetworkChangeReceiver
import com.family.gpstracker.ui.MainActivity
import com.family.gpstracker.worker.SyncWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkChangeReceiver: NetworkChangeReceiver? = null

    companion object {
        const val CHANNEL_ID = "family_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "TrackingService"

        // Интервал записи GPS: по умолчанию каждые 3 минуты (180 000 мс)
        // Для активных прогулок можно 30-60 сек
        var updateIntervalMillis: Long = 180_000L
        var minDistanceMeters: Float = 10.0f
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Частичный WakeLock гарантирует работу CPU при выключенном дисплее
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyTracker::GpsWakeLock").apply {
            setReferenceCounted(false)
        }

        setupLocationCallback()
        registerNetworkObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Служба активна: запись координат"))
                acquireWakeLock()
                startLocationUpdates()
            }
        }
        return START_STICKY // Перезапускать сервис системой при нехватке памяти
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                handleNewLocation(location)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMillis)
            .setMinUpdateIntervalMillis(updateIntervalMillis / 2)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun handleNewLocation(location: Location) {
        val batteryPct = getBatteryLevel()
        val deviceId = getDeviceId(this)

        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val entity = LocationEntity(
                deviceId = deviceId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed * 3.6f, // м/с в км/ч
                batteryLevel = batteryPct,
                timestamp = System.currentTimeMillis(),
                isSynced = false
            )

            val insertedId = db.locationDao().insertLocation(entity)
            Log.d(TAG, "Saved offline point #$insertedId: lat=${location.latitude}, lng=${location.longitude}")

            // Сразу пытаемся передать накопленные данные (если сеть доступна)
            SyncWorker.enqueueImmediateSync(applicationContext)
        }
    }

    private fun registerNetworkObserver() {
        networkChangeReceiver = NetworkChangeReceiver()
        val filter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(networkChangeReceiver, filter)
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 1000L /* 10 мин с продлением */)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire error: ${e.message}")
        }
    }

    private fun stopTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            wakeLock?.let { if (it.isHeld) it.release() }
            networkChangeReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tracking: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновый трекинг геолокации",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Обеспечивает постоянную запись координат для семьи"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Семейный GPS-трекер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("family_tracker_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "device-" + Build.MODEL.replace("\s+".toRegex(), "-").lowercase() + "-" + (1000..9999).random()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }
}
