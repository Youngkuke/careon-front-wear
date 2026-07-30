package com.careon.wear

import com.careon.wear.data.HeartRateAssessment
import com.careon.wear.data.assessHeartRate
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateAssessmentTest {
    @Test
    fun `40 이하는 위험 상태다`() {
        assertEquals(HeartRateAssessment.CRITICAL, assessHeartRate(bpm = 40))
        assertEquals(HeartRateAssessment.CRITICAL, assessHeartRate(bpm = 30))
    }

    @Test
    fun `41부터 59는 상태 확인을 요청한다`() {
        assertEquals(HeartRateAssessment.CHECK_IN, assessHeartRate(bpm = 41))
        assertEquals(HeartRateAssessment.CHECK_IN, assessHeartRate(bpm = 59))
    }

    @Test
    fun `60부터 110은 정상 상태다`() {
        assertEquals(HeartRateAssessment.NORMAL, assessHeartRate(bpm = 60))
        assertEquals(HeartRateAssessment.NORMAL, assessHeartRate(bpm = 110))
    }

    @Test
    fun `111부터 129는 상태 확인을 요청한다`() {
        assertEquals(HeartRateAssessment.CHECK_IN, assessHeartRate(bpm = 111))
        assertEquals(HeartRateAssessment.CHECK_IN, assessHeartRate(bpm = 129))
    }

    @Test
    fun `130 이상은 위험 상태다`() {
        assertEquals(HeartRateAssessment.CRITICAL, assessHeartRate(bpm = 130))
        assertEquals(HeartRateAssessment.CRITICAL, assessHeartRate(bpm = 180))
    }
}
