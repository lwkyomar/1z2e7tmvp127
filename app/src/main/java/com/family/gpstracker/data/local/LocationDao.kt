package com.family.gpstracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity): Long

    // Получаем пачку неотправленных точек (до 100 за раз для надежной пакетной отправки)
    @Query("SELECT * FROM locations WHERE is_synced = 0 ORDER BY timestamp ASC LIMIT 100")
    suspend fun getUnsyncedLocations(): List<LocationEntity>

    // Помечаем отправленные точки
    @Query("UPDATE locations SET is_synced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    // Количество неотправленных точек (для UI-индикатора)
    @Query("SELECT COUNT(*) FROM locations WHERE is_synced = 0")
    fun getUnsyncedCountFlow(): Flow<Int>

    // Общее количество записанных точек
    @Query("SELECT COUNT(*) FROM locations")
    fun getTotalCountFlow(): Flow<Int>

    // Очистка старых синхронизированных точек старше N дней (для экономии памяти)
    @Query("DELETE FROM locations WHERE is_synced = 1 AND timestamp < :thresholdTimestamp")
    suspend fun deleteOldSynced(thresholdTimestamp: Long): Int
}
