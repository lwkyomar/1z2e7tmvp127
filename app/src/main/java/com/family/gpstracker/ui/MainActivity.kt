package com.family.gpstracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.family.gpstracker.R
import com.family.gpstracker.data.local.AppDatabase
import com.family.gpstracker.service.TrackingService
import com.family.gpstracker.worker.SyncWorker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUnsynced: TextView
    private lateinit var tvTotal: TextView
    private lateinit var etDeviceId: EditText
    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseKey: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnSyncNow: Button
    private lateinit var btnBattery: Button

    private var isServiceRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocation || coarseLocation) {
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "Требуется разрешение на геолокацию!", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Фоновое гео разрешено!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Внимание: выберите 'Разрешать в любом режиме' в настройках!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        observeDatabaseStats()
        checkPermissions()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvUnsynced = findViewById(R.id.tvUnsynced)
        tvTotal = findViewById(R.id.tvTotal)
        etDeviceId = findViewById(R.id.etDeviceId)
        etSupabaseUrl = findViewById(R.id.etSupabaseUrl)
        etSupabaseKey = findViewById(R.id.etSupabaseKey)
        btnToggle = findViewById(R.id.btnToggle)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        btnBattery = findViewById(R.id.btnBattery)

        btnToggle.setOnClickListener {
            savePreferences()
            if (isServiceRunning) {
                stopTrackingService()
            } else {
                startTrackingService()
            }
        }

        btnSyncNow.setOnClickListener {
            SyncWorker.enqueueImmediateSync(this)
            Toast.makeText(this, "Принудительная выгрузка запущена", Toast.LENGTH_SHORT).show()
        }

        btnBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        btnToggle.text = "Остановить трекинг"
        tvStatus.text = "Статус: Трекинг АКТИВЕН (фоновый режим)"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
    }

    private fun stopTrackingService() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
        btnToggle.text = "Запустить трекинг"
        tvStatus.text = "Статус: Остановлен"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!bgGranted) {
                AlertDialog.Builder(this)
                    .setTitle("Фоновое определение координат")
                    .setMessage("Для работы при выключенном экране выберите опцию 'Разрешать в любом режиме' (Allow all the time).")
                    .setPositiveButton("Настроить") { _, _ ->
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Оптимизация батареи уже отключена!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeDatabaseStats() {
        val dao = AppDatabase.getDatabase(this).locationDao()
        lifecycleScope.launch {
            dao.getUnsyncedCountFlow().collectLatest { count ->
                tvUnsynced.text = "Неотправленных точек в памяти: $count"
            }
        }
        lifecycleScope.launch {
            dao.getTotalCountFlow().collectLatest { count ->
                tvTotal.text = "Всего сохранено точек: $count"
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("family_tracker_prefs", Context.MODE_PRIVATE)
        val defaultId = "device-" + Build.MODEL.replace("\s+".toRegex(), "-").lowercase()
        etDeviceId.setText(prefs.getString("device_id", defaultId))
        etSupabaseUrl.setText(prefs.getString("supabase_url", "https://xyzcompany.supabase.co"))
        etSupabaseKey.setText(prefs.getString("supabase_key", "eyJhbGciOi...anon-key"))
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("family_tracker_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("device_id", etDeviceId.text.toString().trim())
            .putString("supabase_url", etSupabaseUrl.text.toString().trim())
            .putString("supabase_key", etSupabaseKey.text.toString().trim())
            .apply()
    }
}
