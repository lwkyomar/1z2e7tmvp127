package com.family.gpstracker.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.family.gpstracker.data.local.AppDatabase
import com.family.gpstracker.data.remote.RetrofitClient
import com.family.gpstracker.data.remote.SupabaseLocationDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_WORK_NAME = "SyncLocationsWork"

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Синхронизация запущена...")

        val prefs = appContext.getSharedPreferences("family_tracker_prefs", Context.MODE_PRIVATE)
        val supabaseUrl = prefs.getString("supabase_url", "https://your-project.supabase.co") ?: ""
        val supabaseKey = prefs.getString("supabase_key", "your-anon-key") ?: ""
        val secretToken = prefs.getString("secret_token", "") ?: ""

        val db = AppDatabase.getDatabase(appContext)
        val locationDao = db.locationDao()

        // 1. Выбираем неотправленные точки
        val unsyncedPoints = locationDao.getUnsyncedLocations()
        if (unsyncedPoints.isEmpty()) {
            Log.d(TAG, "Нет новых оффлайн точек для синхронизации.")
            return Result.success()
        }

        Log.d(TAG, "Найдено ${unsyncedPoints.size} неотправленных точек. Подготовка к пакетной передаче...")

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val dtoList = unsyncedPoints.map { entity ->
            SupabaseLocationDto(
                deviceId = entity.deviceId,
                latitude = entity.latitude,
                longitude = entity.longitude,
                accuracy = entity.accuracy,
                speed = entity.speed,
                batteryLevel = entity.batteryLevel,
                timestamp = entity.timestamp,
                createdAt = isoFormatter.format(Date(entity.timestamp))
            )
        }

        return try {
            val api = RetrofitClient.getApi(supabaseUrl)
            val response = api.insertLocations(
                apiKey = supabaseKey,
                bearerToken = "Bearer $supabaseKey",
                secretToken = secretToken.ifEmpty { null },
                locations = dtoList
            )

            if (response.isSuccessful) {
                // 2. Отмечаем в Room как синхронизированные
                val ids = unsyncedPoints.map { it.id }
                locationDao.markAsSynced(ids)
                Log.d(TAG, "Успешно выгружено ${ids.size} точек в Supabase!")

                // 3. Удаляем старые записи старше 7 дней для очистки памяти
                val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 3600 * 1000)
                locationDao.deleteOldSynced(sevenDaysAgo)

                // Если в базе еще остались неотправленные, повторяем
                if (locationDao.getUnsyncedLocations().isNotEmpty()) {
                    enqueueImmediateSync(appContext)
                }

                Result.success()
            } else {
                Log.e(TAG, "Ошибка API ${response.code()}: ${response.errorBody()?.string()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сети при отправке: ${e.message}")
            Result.retry()
        }
    }
}
