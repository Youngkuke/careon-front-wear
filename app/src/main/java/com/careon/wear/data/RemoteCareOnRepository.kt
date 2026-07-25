package com.careon.wear.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** HTTP implementation of the agreed general-backend Wear API. API JSON stays snake_case here. */
class RemoteCareOnRepository(context: Context) : CareOnRepository {
    private val preferences = context.getSharedPreferences("wear_session", Context.MODE_PRIVATE)

    override suspend fun pair(pairingCode: String): Result<WearProfile> = runCatching {
        val body = JSONObject().put("code", pairingCode)
        val response = request("POST", "/api/wear/auth/pair", body)
        val cared = response.getJSONObject("cared")
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, response.getString("wear_access_token"))
            .putString(KEY_REFRESH_TOKEN, response.getString("wear_refresh_token"))
            .putLong(KEY_CARED_ID, cared.getLong("cared_id"))
            .putString(KEY_CARED_RELATION, cared.optString("cared_relation", "돌봄 대상자"))
            .apply()
        WearProfile(
            caredId = cared.getLong("cared_id"),
            // The service intentionally does not collect a care recipient's name.
            displayName = cared.optString("cared_relation", "돌봄 대상자"),
            emergencyContactName = "보호자",
            heartRateCheckInThreshold = DEFAULT_HEART_RATE_THRESHOLD,
        )
    }

    override suspend fun restoreSession(): WearProfile? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val caredId = preferences.getLong(KEY_CARED_ID, NO_CARED_ID)
        if (accessToken.isBlank() || caredId == NO_CARED_ID) return null
        return WearProfile(
            caredId = caredId,
            displayName = preferences.getString(KEY_CARED_RELATION, null) ?: "돌봄 대상자",
            emergencyContactName = "보호자",
            heartRateCheckInThreshold = DEFAULT_HEART_RATE_THRESHOLD,
        )
    }

    override suspend fun measureHeartRate(bpm: Int) = HeartRateReading(bpm, Instant.now(), "WATCH")

    override suspend fun createEmergency(trigger: EmergencyTrigger, heartRateBpm: Int?, location: LocationSnapshot?, locationStatus: LocationStatus): EmergencyEvent {
        val body = JSONObject()
            .put("trigger", trigger.name)
            .put("requested_at", Instant.now().toString())
            .put("location_status", locationStatus.name)
            .put("location", location?.toEmergencyJson() ?: JSONObject.NULL)
        if (heartRateBpm == null) body.put("heart_rate_bpm", JSONObject.NULL) else body.put("heart_rate_bpm", heartRateBpm)
        val response = request("POST", "/api/wear/emergency-events", body, UUID.randomUUID().toString())
        return EmergencyEvent(
            id = response.getLong("event_id").toString(),
            trigger = trigger,
            heartRateBpm = heartRateBpm,
            requestedAt = Instant.parse(response.getString("requested_at")),
            status = EmergencyStatus.valueOf(response.getString("status")),
            location = location,
        )
    }

    override suspend fun getEmergency(eventId: String): EmergencyEvent {
        val response = request("GET", "/api/wear/emergency-events/$eventId")
        return EmergencyEvent(eventId, EmergencyTrigger.MANUAL_SOS, null, Instant.now(), EmergencyStatus.valueOf(response.getString("status")))
    }

    override suspend fun getSafeZone(): SafeZone? {
        val response = requestAllowNoContent("GET", "/api/wear/safe-zone") ?: return null
        return SafeZone(response.getLong("safe_zone_id"), response.getString("name"), response.getDouble("latitude"), response.getDouble("longitude"), response.getDouble("radius_meters"), response.getBoolean("enabled"))
    }

    override suspend fun createSafeZoneEvent(status: SafeZoneStatus, location: LocationSnapshot): SafeZoneEvent {
        val zone = getSafeZone() ?: error("활성 안심 구역이 없어요.")
        val body = JSONObject()
            .put("safe_zone_id", zone.id)
            .put("status", status.name)
            .put("detected_at", Instant.now().toString())
            .put("location", location.toSafeZoneJson())
        val response = request("POST", "/api/wear/safe-zone-events", body, UUID.randomUUID().toString())
        return SafeZoneEvent(response.getLong("event_id").toString(), SafeZoneStatus.valueOf(response.getString("status")), location)
    }

    override suspend fun respondToSafeZoneEvent(eventId: String, response: SafeZoneStatus): SafeZoneEvent {
        val payload = request("PATCH", "/api/wear/safe-zone-events/$eventId/response", JSONObject().put("response", response.name))
        return SafeZoneEvent(payload.getLong("event_id").toString(), response, LocationSnapshot(0.0, 0.0, 0f, Instant.now(), LocationSource.CURRENT))
    }

    override suspend fun getLiveLocationTracking(): LiveLocationTracking {
        val response = request("GET", "/api/wear/live-location/tracking")
        return LiveLocationTracking(
            enabled = response.optBoolean("enabled", false),
            intervalSeconds = response.optInt("interval_seconds", DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS)
                .coerceIn(MINIMUM_LIVE_LOCATION_INTERVAL_SECONDS, MAXIMUM_LIVE_LOCATION_INTERVAL_SECONDS),
        )
    }

    override suspend fun uploadLiveLocation(location: LocationSnapshot) {
        request("POST", "/api/wear/live-location", location.toLiveLocationJson())
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject = withContext(Dispatchers.IO) {
        rawRequest(method, path, body, idempotencyKey, allowNoContent = false) ?: error("빈 응답을 받았어요.")
    }

    private suspend fun requestAllowNoContent(method: String, path: String): JSONObject? = withContext(Dispatchers.IO) {
        rawRequest(method, path, null, null, allowNoContent = true)
    }

    private fun rawRequest(method: String, path: String, body: JSONObject?, idempotencyKey: String?, allowNoContent: Boolean): JSONObject? {
        var response = perform(method, path, body, idempotencyKey, preferences.getString(KEY_ACCESS_TOKEN, null))
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && refreshToken()) response = perform(method, path, body, idempotencyKey, preferences.getString(KEY_ACCESS_TOKEN, null))
        if (response.code == HttpURLConnection.HTTP_NO_CONTENT && allowNoContent) return null
        if (response.code !in 200..299) throw IllegalStateException(response.message)
        return JSONObject(response.body)
    }

    private fun refreshToken(): Boolean = runCatching {
        val token = preferences.getString(KEY_REFRESH_TOKEN, null) ?: return false
        val response = perform("POST", "/api/wear/auth/refresh", JSONObject().put("wear_refresh_token", token), null, null)
        if (response.code !in 200..299) return false
        val json = JSONObject(response.body)
        preferences.edit().putString(KEY_ACCESS_TOKEN, json.getString("wear_access_token")).putString(KEY_REFRESH_TOKEN, json.getString("wear_refresh_token")).apply()
        true
    }.getOrDefault(false)

    private fun perform(method: String, path: String, body: JSONObject?, idempotencyKey: String?, accessToken: String?): HttpResult {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (idempotencyKey != null) setRequestProperty("Idempotency-Key", idempotencyKey)
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body.toString().toByteArray()) } }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(code, text.ifBlank { "요청을 처리하지 못했어요." })
    }

    private fun LocationSnapshot.toEmergencyJson() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("accuracy_meters", accuracyMeters).put("captured_at", capturedAt.toString()).put("source", source.name)
    private fun LocationSnapshot.toSafeZoneJson() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("accuracy_meters", accuracyMeters).put("captured_at", capturedAt.toString())
    private fun LocationSnapshot.toLiveLocationJson() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("accuracy_meters", accuracyMeters).put("captured_at", capturedAt.toString()).put("source", source.name)
    private data class HttpResult(val code: Int, val body: String) { val message get() = runCatching { JSONObject(body).optString("message", body) }.getOrDefault(body) }
    private companion object {
        const val BASE_URL = "https://api.careon.site"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_CARED_ID = "cared_id"
        const val KEY_CARED_RELATION = "cared_relation"
        const val NO_CARED_ID = -1L
        const val DEFAULT_HEART_RATE_THRESHOLD = 110
        const val DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS = 10
        const val MINIMUM_LIVE_LOCATION_INTERVAL_SECONDS = 5
        const val MAXIMUM_LIVE_LOCATION_INTERVAL_SECONDS = 60
    }
}
