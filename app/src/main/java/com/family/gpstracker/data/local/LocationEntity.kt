package com.family.gpstracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "locations",
    indices = [
        Index(value = ["is_synced"]),
        Index(value = ["timestamp"])
    ]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float,

    @ColumnInfo(name = "speed")
    val speed: Float,

    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long, // Unix epoch milliseconds

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false // Флаг: true если успешно доставлено на сервер
)
