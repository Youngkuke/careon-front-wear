package com.careon.wear.data

import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID

data class WearProfile(
    val caredId: Long,
    val displayName: String,
    val emergencyContactName: String,
    val heartRateCheckInThreshold: Int,
)

data class HeartRateReading(
    val bpm: Int,
    val measuredAt: Instant,
    val source: String = "DEMO",
)

enum class HeartRateAssessment {
    NORMAL,
    CHECK_IN,
}

fun assessHeartRate(bpm: Int, threshold: Int): HeartRateAssessment =
    if (bpm >= threshold) HeartRateAssessment.CHECK_IN else HeartRateAssessment.NORMAL

enum class EmergencyTrigger {
    HEART_RATE_CHECK_IN,
    MANUAL_SOS,
}

enum class EmergencyStatus {
    PENDING,
    ACKNOWLEDGED,
}

data class EmergencyEvent(
    val id: String,
    val trigger: EmergencyTrigger,
    val heartRateBpm: Int?,
    val requestedAt: Instant,
    val status: EmergencyStatus,
    val acknowledgedByName: String? = null,
)

interface CareOnRepository {
    suspend fun pair(pairingCode: String): Result<WearProfile>
    suspend fun measureHeartRate(bpm: Int): HeartRateReading
    suspend fun createEmergency(
        trigger: EmergencyTrigger,
        heartRateBpm: Int?,
    ): EmergencyEvent

    suspend fun getEmergency(eventId: String): EmergencyEvent
}

/**
 * API/DB 연결 전 화면 흐름을 검증하기 위한 임시 구현이다.
 * 실제 연결 때는 동일한 CareOnRepository 계약을 Retrofit 기반 구현으로 교체한다.
 */
class DemoCareOnRepository : CareOnRepository {
    private val profile = WearProfile(
        caredId = 7,
        displayName = "어머니",
        emergencyContactName = "김보호",
        heartRateCheckInThreshold = 110,
    )
    private val emergencies = mutableMapOf<String, EmergencyEvent>()

    override suspend fun pair(pairingCode: String): Result<WearProfile> {
        delay(450)

        return if (pairingCode == DEMO_PAIRING_CODE) {
            Result.success(profile)
        } else {
            Result.failure(IllegalArgumentException("데모 연결 코드는 $DEMO_PAIRING_CODE 입니다."))
        }
    }

    override suspend fun measureHeartRate(bpm: Int): HeartRateReading {
        delay(1_100)
        return HeartRateReading(bpm = bpm, measuredAt = Instant.now())
    }

    override suspend fun createEmergency(
        trigger: EmergencyTrigger,
        heartRateBpm: Int?,
    ): EmergencyEvent {
        delay(550)
        val event = EmergencyEvent(
            id = UUID.randomUUID().toString(),
            trigger = trigger,
            heartRateBpm = heartRateBpm,
            requestedAt = Instant.now(),
            status = EmergencyStatus.PENDING,
        )
        emergencies[event.id] = event
        return event
    }

    override suspend fun getEmergency(eventId: String): EmergencyEvent {
        delay(150)
        val current = emergencies.getValue(eventId)
        val elapsedMillis = Instant.now().toEpochMilli() - current.requestedAt.toEpochMilli()

        if (current.status == EmergencyStatus.PENDING && elapsedMillis >= DEMO_ACK_DELAY_MILLIS) {
            return current.copy(
                status = EmergencyStatus.ACKNOWLEDGED,
                acknowledgedByName = profile.emergencyContactName,
            ).also { emergencies[eventId] = it }
        }

        return current
    }

    companion object {
        const val DEMO_PAIRING_CODE = "111111"
        private const val DEMO_ACK_DELAY_MILLIS = 3_000L
    }
}
