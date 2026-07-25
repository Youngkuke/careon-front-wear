package com.careon.wear

import com.careon.wear.location.SafeZoneEvaluator
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZoneEvaluatorTest {
    @Test fun `same coordinate has zero distance`() {
        assertTrue(SafeZoneEvaluator.distanceMeters(37.4965, 126.9572, 37.4965, 126.9572) < 0.1)
    }

    @Test fun `one kilometer is measurably outside`() {
        assertTrue(SafeZoneEvaluator.distanceMeters(37.4965, 126.9572, 37.5055, 126.9572) > 900)
    }
}
