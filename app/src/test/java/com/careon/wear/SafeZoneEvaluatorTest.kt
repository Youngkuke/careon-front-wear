package com.careon.wear

import com.careon.wear.data.LocationSnapshot
import com.careon.wear.data.LocationSource
import com.careon.wear.data.SafeZone
import com.careon.wear.data.SafeZoneStatus
import com.careon.wear.location.SafeZoneEvaluator
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneEvaluatorTest {
    @Test fun `same coordinate has zero distance`() {
        assertTrue(SafeZoneEvaluator.distanceMeters(37.4965, 126.9572, 37.4965, 126.9572) < 0.1)
    }

    @Test fun `one kilometer is measurably outside`() {
        assertTrue(SafeZoneEvaluator.distanceMeters(37.4965, 126.9572, 37.5055, 126.9572) > 900)
    }

    @Test fun `unchanged location timestamp confirms departure after two evaluation cycles`() {
        var now = 1_000L
        val evaluator = SafeZoneEvaluator { now }
        val zone = SafeZone(1, "서울", 37.5665, 126.9780, 100.0)
        val unchangedLocation = LocationSnapshot(
            latitude = 37.4563,
            longitude = 126.7052,
            accuracyMeters = 10f,
            capturedAt = Instant.ofEpochMilli(500),
            source = LocationSource.CURRENT,
        )

        assertEquals(SafeZoneStatus.OUTSIDE_CANDIDATE, evaluator.evaluate(zone, unchangedLocation))
        now += 10_000
        assertEquals(SafeZoneStatus.OUTSIDE_CONFIRMED, evaluator.evaluate(zone, unchangedLocation))
    }
}
