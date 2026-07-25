package com.careon.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** A short-lived reading from the watch's physical heart-rate sensor. */
interface HeartRateSensorClient {
    fun requestReading(onReading: (Int) -> Unit, onError: (String) -> Unit)
    fun cancelReading()
}

/**
 * Uses Android's heart-rate sensor directly. This reads the physical sensor on a watch and the
 * Heart rate value configured in Emulator > Extended Controls > Virtual Sensors on an emulator.
 */
class AndroidHeartRateSensorClient(context: Context) : HeartRateSensorClient, SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var onReading: ((Int) -> Unit)? = null

    override fun requestReading(onReading: (Int) -> Unit, onError: (String) -> Unit) {
        cancelReading()
        if (heartRateSensor == null) {
            onError("이 워치에서는 심박 센서를 사용할 수 없어요.")
            return
        }
        this.onReading = onReading
        if (!sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            this.onReading = null
            onError("심박 센서를 시작하지 못했어요.")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val bpm = event.values.firstOrNull()?.toInt()?.takeIf { it > 0 } ?: return
        val listener = onReading ?: return
        cancelReading()
        listener(bpm)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun cancelReading() {
        sensorManager.unregisterListener(this)
        onReading = null
    }
}
