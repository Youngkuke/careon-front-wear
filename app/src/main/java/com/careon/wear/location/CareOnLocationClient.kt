package com.careon.wear.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.careon.wear.data.LocationSnapshot
import com.careon.wear.data.LocationSource
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

interface CareOnLocationClient {
    suspend fun getLocation(): LocationResult
}

sealed interface LocationResult {
    data class Available(val snapshot: LocationSnapshot) : LocationResult
    data object Unavailable : LocationResult
    data object GpsDisabled : LocationResult
}

class FusedCareOnLocationClient(context: Context) : CareOnLocationClient {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getLocation(): LocationResult = withContext(Dispatchers.IO) {
        val current = runCatching {
            Tasks.await(
                client.getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setDurationMillis(5_000)
                        .build(),
                    null,
                ),
                6,
                TimeUnit.SECONDS,
            )
        }.getOrNull()
        current?.toSnapshot(LocationSource.CURRENT)?.let { return@withContext LocationResult.Available(it) }

        val last = runCatching { Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS) }.getOrNull()
        val snapshot = last?.toSnapshot(LocationSource.LAST_KNOWN)
        if (snapshot != null && Instant.now().minusSeconds(120).isBefore(snapshot.capturedAt)) {
            LocationResult.Available(snapshot)
        } else {
            LocationResult.Unavailable
        }
    }

    private fun Location.toSnapshot(source: LocationSource) = LocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        capturedAt = Instant.ofEpochMilli(time),
        source = source,
    )
}

/** Emulator and offline fallback used before a location permission is granted. */
class DemoLocationClient : CareOnLocationClient {
    override suspend fun getLocation() = LocationResult.Available(
        LocationSnapshot(37.4965, 126.9572, 18f, Instant.now(), LocationSource.CURRENT),
    )
}
