package com.careon.wear

import com.careon.wear.data.HeartRateAssessment
import com.careon.wear.data.assessHeartRate
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateAssessmentTest {
    @Test
    fun `threshold 미만은 정상 상태다`() {
        assertEquals(HeartRateAssessment.NORMAL, assessHeartRate(bpm = 109, threshold = 110))
    }

    @Test
    fun `threshold 이상은 상태 확인을 요청한다`() {
        assertEquals(HeartRateAssessment.CHECK_IN, assessHeartRate(bpm = 110, threshold = 110))
    }
}
