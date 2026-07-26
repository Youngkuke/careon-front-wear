package com.careon.wear.location

import com.careon.wear.data.LocationSnapshot
import com.careon.wear.data.SafeZone
import com.careon.wear.data.SafeZoneStatus
import kotlin.math.*

class SafeZoneEvaluator {
    private var outsideCount = 0
    private var firstOutsideAt: Long? = null

    fun evaluate(zone: SafeZone, location: LocationSnapshot): SafeZoneStatus {
        val distance = distanceMeters(zone.latitude, zone.longitude, location.latitude, location.longitude)
        if (distance < zone.radiusMeters - 20) { reset(); return SafeZoneStatus.INSIDE }
        val margin = max(location.accuracyMeters.toDouble(), 30.0)
        if (distance <= zone.radiusMeters + margin) { reset(); return SafeZoneStatus.INSIDE }
        outsideCount += 1
        if (firstOutsideAt == null) firstOutsideAt = location.capturedAt.toEpochMilli()
        return if (outsideCount >= 2 && location.capturedAt.toEpochMilli() - firstOutsideAt!! >= 30_000) SafeZoneStatus.OUTSIDE_CONFIRMED
        else SafeZoneStatus.OUTSIDE_CANDIDATE
    }

    fun reset() { outsideCount = 0; firstOutsideAt = null }

    companion object {
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
