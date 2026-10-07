package com.family.gpstracker.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

// DTO для отправки в таблицу locations в Supabase
data class SupabaseLocationDto(
    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("latitude")
    val latitude: Double,

    @SerializedName("longitude")
    val longitude: Double,

    @SerializedName("accuracy")
    val accuracy: Float,

    @SerializedName("speed")
    val speed: Float,

    @SerializedName("battery_level")
    val batteryLevel: Int,

    @SerializedName("timestamp")
    val timestamp: Long,

    @SerializedName("created_at")
    val createdAt: String // ISO 8601 строка
)

interface SupabaseApi {
    // PostgREST поддерживает вставку массива JSON объектов одной транзакцией
    @Headers(
        "Content-Type: application/json",
        "Prefer: return=minimal"
    )
    @POST("rest/v1/locations")
    suspend fun insertLocations(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("x-sync-secret") secretToken: String?,
        @Body locations: List<SupabaseLocationDto>
    ): Response<Unit>
}
