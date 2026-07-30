package com.careon.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.careon.wear.data.CareOnRepository
import com.careon.wear.data.DemoCareOnRepository
import com.careon.wear.data.EmergencyEvent
import com.careon.wear.data.EmergencyStatus
import com.careon.wear.data.EmergencyTrigger
import com.careon.wear.data.HeartRateAssessment
import com.careon.wear.data.HeartRateReading
import com.careon.wear.data.WearProfile
import com.careon.wear.data.WearConnectionInfo
import com.careon.wear.data.WearSessionExpiredException
import com.careon.wear.data.assessHeartRate
import com.careon.wear.data.LocationSnapshot
import com.careon.wear.data.LocationStatus
import com.careon.wear.data.SafeZone
import com.careon.wear.data.SafeZoneEvent
import com.careon.wear.data.SafeZoneStatus
import com.careon.wear.location.CareOnLocationClient
import com.careon.wear.location.DemoLocationClient
import com.careon.wear.location.LocationResult
import com.careon.wear.location.SafeZoneEvaluator
import com.careon.wear.sensor.HeartRateSensorClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class WearScreen {
    PAIRING,
    HOME,
    MEASURING,
    RESULT,
    CHECK_IN,
    SOS,
    WAITING,
    ACKNOWLEDGED,
    LOCATION_PERMISSION,
    SAFE_ZONE_EXIT,
    SETTINGS,
}

data class CareOnWearUiState(
    val screen: WearScreen = WearScreen.PAIRING,
    val pairingCode: String = "",
    val pairingError: String? = null,
    val isPairing: Boolean = false,
    val profile: WearProfile? = null,
    val connectionInfo: WearConnectionInfo? = null,
    val isLoadingConnection: Boolean = false,
    val isDisconnecting: Boolean = false,
    val isMeasuring: Boolean = false,
    val heartRatePermissionGranted: Boolean = false,
    val requestHeartRatePermission: Boolean = false,
    val latestReading: HeartRateReading? = null,
    val nextAutomaticHeartRateAt: Instant? = null,
    val assessment: HeartRateAssessment? = null,
    val emergency: EmergencyEvent? = null,
    val actionError: String? = null,
    val locationGranted: Boolean = false,
    val isFetchingLocation: Boolean = false,
    val latestLocation: LocationSnapshot? = null,
    val locationMessage: String = "위치 확인 전",
    val locationStatus: LocationStatus = LocationStatus.UNAVAILABLE,
    val safeZone: SafeZone? = null,
    val safeZoneStatus: SafeZoneStatus = SafeZoneStatus.UNKNOWN,
    val safeZoneEvent: SafeZoneEvent? = null,
)

class CareOnWearViewModel(
    private val repository: CareOnRepository = DemoCareOnRepository(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareOnWearUiState())
    val state: StateFlow<CareOnWearUiState> = mutableState.asStateFlow()
    private var emergencyPollingJob: Job? = null
    private var automaticHeartRateJob: Job? = null
    private var liveLocationTrackingJob: Job? = null
    private var safeZoneMonitoringJob: Job? = null
    private var safeZoneResponseTimeoutJob: Job? = null
    private var deviceStatusJob: Job? = null
    private var locationClient: CareOnLocationClient = DemoLocationClient()
    private var heartRateSensorClient: HeartRateSensorClient? = null
    private var heartRateTimeoutJob: Job? = null
    private var heartRateCheckInTimeoutJob: Job? = null
    private var pendingHeartRateCheckInReading: HeartRateReading? = null
    private var automaticHeartRateCheckInsSuppressedUntil: Instant? = null
    private val safeZoneEvaluator = SafeZoneEvaluator()
    private var monitoredZoneKey: String? = null
    private var departureConfirmed = false
    private var batteryPercentProvider: (() -> Int?)? = null

    init {
        restoreSession()
    }

    fun setLocationClient(client: CareOnLocationClient) { locationClient = client }
    fun setHeartRateSensorClient(client: HeartRateSensorClient) {
        heartRateSensorClient = client
        // Session restoration can complete before Compose creates the sensor client.
        // Re-evaluate the foreground sampler once the actual sensor is available.
        startAutomaticHeartRateMeasurement()
    }
    fun setBatteryPercentProvider(provider: () -> Int?) { batteryPercentProvider = provider }

    private fun restoreSession() = viewModelScope.launch {
        repository.restoreSession()?.let { profile ->
            mutableState.update {
                it.copy(
                    profile = profile,
                    screen = if (it.locationGranted) WearScreen.HOME else WearScreen.LOCATION_PERMISSION,
                )
            }
            startAutomaticHeartRateMeasurement()
            startLiveLocationTracking()
            loadSafeZone()
            restoreActiveSafeZoneEvent()
            startDeviceStatusReporting()
        }
    }

    fun onHeartRatePermission(granted: Boolean) {
        mutableState.update {
            it.copy(
                heartRatePermissionGranted = granted,
                requestHeartRatePermission = false,
                actionError = if (granted) null else "심박수 확인을 위해 센서 권한이 필요해요.",
            )
        }
        if (granted) {
            if (state.value.profile != null) measureHeartRate()
            startAutomaticHeartRateMeasurement()
        }
    }

    fun onLocationPermission(granted: Boolean) {
        mutableState.update {
            it.copy(
                locationGranted = granted,
                screen = if (it.profile != null && it.screen == WearScreen.LOCATION_PERMISSION) WearScreen.HOME else it.screen,
                locationMessage = if (granted) "위치 확인 가능" else "위치 권한 없이 이용 중",
                locationStatus = if (granted) it.locationStatus else LocationStatus.PERMISSION_DENIED,
            )
        }
        if (granted) {
            refreshLocation()
            startLiveLocationTracking()
            startSafeZoneMonitoring()
        } else {
            liveLocationTrackingJob?.cancel()
            safeZoneMonitoringJob?.cancel()
        }
    }

    fun appendPairingDigit(digit: String) {
        mutableState.update { current ->
            if (current.pairingCode.length == 6) current
            else current.copy(pairingCode = current.pairingCode + digit, pairingError = null)
        }
    }

    fun removePairingDigit() {
        mutableState.update { current ->
            current.copy(pairingCode = current.pairingCode.dropLast(1), pairingError = null)
        }
    }

    fun pair() {
        val pairingCode = state.value.pairingCode

        if (pairingCode.length != 6) {
            mutableState.update { it.copy(pairingError = "6자리 코드를 입력해주세요.") }
            return
        }

        mutableState.update { it.copy(isPairing = true, pairingError = null) }
        viewModelScope.launch {
            repository.pair(pairingCode)
                .onSuccess { profile ->
                    mutableState.update {
                        it.copy(
                            isPairing = false,
                            profile = profile,
                            screen = if (it.locationGranted) WearScreen.HOME else WearScreen.LOCATION_PERMISSION,
                        )
                    }
                    startAutomaticHeartRateMeasurement()
                    startLiveLocationTracking()
                    loadSafeZone()
                    restoreActiveSafeZoneEvent()
                    startDeviceStatusReporting()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isPairing = false, pairingError = error.message ?: "연결하지 못했어요.")
                    }
                }
        }
    }

    fun measureHeartRate() {
        if (state.value.profile == null) return
        if (!state.value.heartRatePermissionGranted) {
            mutableState.update { it.copy(requestHeartRatePermission = true, actionError = null) }
            return
        }
        val sensor = heartRateSensorClient
        if (sensor == null) {
            mutableState.update { it.copy(actionError = "심박 센서를 준비하지 못했어요.") }
            return
        }
        mutableState.update { it.copy(isMeasuring = true, screen = WearScreen.MEASURING, actionError = null) }
        sensor.requestReading(
            onReading = ::onHeartRateReading,
            onError = ::onHeartRateMeasurementError,
        )
        heartRateTimeoutJob?.cancel()
        heartRateTimeoutJob = viewModelScope.launch {
            delay(20_000)
            if (state.value.isMeasuring) onHeartRateMeasurementError("심박수를 측정하지 못했어요. 워치를 손목에 착용한 뒤 다시 시도해주세요.")
        }
    }

    private fun onHeartRateReading(bpm: Int) {
        heartRateTimeoutJob?.cancel()
        val reading = HeartRateReading(bpm = bpm, measuredAt = java.time.Instant.now(), source = "WATCH_SENSOR")
        val assessment = assessHeartRate(reading.bpm)
        recordHeartRate(reading)
        when (assessment) {
            HeartRateAssessment.NORMAL -> mutableState.update {
                it.copy(assessment = assessment, isMeasuring = false, latestReading = reading, screen = WearScreen.RESULT)
            }
            HeartRateAssessment.CHECK_IN -> enterHeartRateCheckIn(reading)
            HeartRateAssessment.CRITICAL -> sendCriticalHeartRateEmergency(reading)
        }
    }

    private fun onHeartRateMeasurementError(message: String) {
        heartRateTimeoutJob?.cancel()
        heartRateSensorClient?.cancelReading()
        mutableState.update { it.copy(isMeasuring = false, screen = WearScreen.HOME, actionError = message) }
    }

    /**
     * Foreground-only periodic check. It never invents a BPM: each cycle waits for a new event
     * from the physical sensor, then updates the home value and opens a check-in if necessary.
     */
    private fun startAutomaticHeartRateMeasurement() {
        automaticHeartRateJob?.cancel()
        if (!state.value.heartRatePermissionGranted || heartRateSensorClient == null) {
            mutableState.update { it.copy(nextAutomaticHeartRateAt = null) }
            return
        }

        automaticHeartRateJob = viewModelScope.launch {
            while (true) {
                val nextMeasurementAt = Instant.now().plusMillis(AUTOMATIC_HEART_RATE_INTERVAL_MILLIS)
                mutableState.update { it.copy(nextAutomaticHeartRateAt = nextMeasurementAt) }
                delay(AUTOMATIC_HEART_RATE_INTERVAL_MILLIS)
                if (
                    state.value.profile == null ||
                    state.value.screen != WearScreen.HOME
                ) continue
                val sensor = heartRateSensorClient ?: continue
                sensor.requestReading(
                    onReading = ::onAutomaticHeartRateReading,
                    // A sensor can legitimately have no fresh value in this interval. Keep the
                    // previous reading and try again next cycle without interrupting the user.
                    onError = {},
                )
            }
        }
    }

    private fun onAutomaticHeartRateReading(bpm: Int) {
        val reading = HeartRateReading(bpm = bpm, measuredAt = java.time.Instant.now(), source = "WATCH_SENSOR")
        val assessment = assessHeartRate(reading.bpm)
        val currentScreen = state.value.screen
        mutableState.update { current ->
            current.copy(
                latestReading = reading,
                assessment = assessment,
            )
        }
        recordHeartRate(reading)
        val checkInSuppressed = automaticHeartRateCheckInsSuppressedUntil?.let { Instant.now().isBefore(it) } == true
        when {
            assessment == HeartRateAssessment.CRITICAL && currentScreen == WearScreen.HOME ->
                sendCriticalHeartRateEmergency(reading)
            assessment == HeartRateAssessment.CHECK_IN && currentScreen == WearScreen.HOME && !checkInSuppressed ->
                enterHeartRateCheckIn(reading)
        }
    }

    private fun sendCriticalHeartRateEmergency(reading: HeartRateReading) {
        heartRateCheckInTimeoutJob?.cancel()
        pendingHeartRateCheckInReading = null
        mutableState.update {
            it.copy(
                assessment = HeartRateAssessment.CRITICAL,
                isMeasuring = false,
                latestReading = reading,
                actionError = null,
            )
        }
        createEmergency(
            trigger = EmergencyTrigger.HEART_RATE_CHECK_IN,
            heartRateBpm = reading.bpm,
            failureScreen = WearScreen.HOME,
        )
    }

    private fun enterHeartRateCheckIn(reading: HeartRateReading) {
        heartRateCheckInTimeoutJob?.cancel()
        pendingHeartRateCheckInReading = reading
        mutableState.update {
            it.copy(
                assessment = HeartRateAssessment.CHECK_IN,
                isMeasuring = false,
                latestReading = reading,
                screen = WearScreen.CHECK_IN,
            )
        }
        heartRateCheckInTimeoutJob = viewModelScope.launch {
            delay(HEART_RATE_CHECK_IN_TIMEOUT_MILLIS)
            if (
                state.value.screen == WearScreen.CHECK_IN &&
                pendingHeartRateCheckInReading?.measuredAt == reading.measuredAt
            ) {
                pendingHeartRateCheckInReading = null
                createEmergency(EmergencyTrigger.HEART_RATE_CHECK_IN, reading.bpm)
            }
        }
    }

    /** Heart-rate storage is best effort: a temporary network failure must not block SOS/check-in. */
    private fun recordHeartRate(reading: HeartRateReading) = viewModelScope.launch {
        try {
            repository.recordHeartRate(reading)
        } catch (error: WearSessionExpiredException) {
            handleExpiredSession(error)
        } catch (_: Exception) {
            // The next foreground sensor sample is sent again; never fabricate a reading locally.
        }
    }

    /** Foreground report only: sufficient for the emulator demo and avoids a hidden background service. */
    private fun startDeviceStatusReporting() {
        deviceStatusJob?.cancel()
        deviceStatusJob = viewModelScope.launch {
            while (state.value.profile != null) {
                batteryPercentProvider?.invoke()?.let { percent ->
                    try { repository.reportDeviceStatus(percent) }
                    catch (error: WearSessionExpiredException) { handleExpiredSession(error); return@launch }
                    catch (_: Exception) { /* retry on the next 15-minute foreground report */ }
                }
                delay(DEVICE_STATUS_REPORT_INTERVAL_MILLIS)
            }
        }
    }

    fun openCheckIn() {
        state.value.latestReading?.let(::enterHeartRateCheckIn)
    }

    fun openSettings() {
        mutableState.update { it.copy(screen = WearScreen.SETTINGS, actionError = null) }
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingConnection = true) }
            try {
                val info = repository.getConnectionInfo()
                mutableState.update { it.copy(connectionInfo = info, isLoadingConnection = false) }
            } catch (error: WearSessionExpiredException) {
                handleExpiredSession(error)
            } catch (error: Exception) {
                mutableState.update { it.copy(isLoadingConnection = false, actionError = error.message ?: "연결 정보를 불러오지 못했어요.") }
            }
        }
    }

    fun disconnectWear() = viewModelScope.launch {
        mutableState.update { it.copy(isDisconnecting = true, actionError = null) }
        try {
            repository.disconnectWear()
            clearLocalConnection("연결이 해제됐어요.\n새 연결 코드를 입력해주세요.")
        } catch (error: WearSessionExpiredException) {
            // DELETE invalidates this token. A retry after a network timeout therefore returns
            // 401 even when the server already completed the disconnect successfully.
            clearLocalConnection("연결이 해제됐어요.\n새 연결 코드를 입력해주세요.")
        } catch (error: Exception) {
            mutableState.update { it.copy(isDisconnecting = false, actionError = error.message ?: "연결을 해제하지 못했어요.") }
        }
    }

    fun sayOkay() {
        heartRateCheckInTimeoutJob?.cancel()
        pendingHeartRateCheckInReading = null
        suppressAutomaticHeartRateCheckIns()
        mutableState.update {
            it.copy(screen = WearScreen.HOME, assessment = null, actionError = null)
        }
    }

    fun openSos() = mutableState.update { it.copy(screen = WearScreen.SOS, actionError = null) }

    fun prepareSosLocation() = refreshLocation()

    fun refreshLocation() {
        if (!state.value.locationGranted) {
            mutableState.update { it.copy(locationMessage = "위치 권한이 없어요") }
            return
        }
        mutableState.update { it.copy(isFetchingLocation = true) }
        viewModelScope.launch {
            when (val result = locationClient.getLocation()) {
                is LocationResult.Available -> mutableState.update { it.copy(isFetchingLocation = false, latestLocation = result.snapshot, locationMessage = if (result.snapshot.source.name == "CURRENT") "위치 확인됨" else "최근 위치 사용", locationStatus = if (result.snapshot.source.name == "CURRENT") LocationStatus.CURRENT else LocationStatus.LAST_KNOWN) }
                LocationResult.GpsDisabled -> mutableState.update { it.copy(isFetchingLocation = false, locationMessage = "GPS를 사용할 수 없어요", locationStatus = LocationStatus.GPS_DISABLED) }
                LocationResult.Unavailable -> mutableState.update { it.copy(isFetchingLocation = false, locationMessage = "위치를 확인할 수 없어요", locationStatus = LocationStatus.UNAVAILABLE) }
            }
        }
    }

    /**
     * The guardian explicitly turns this on from the mobile app. The watch checks that setting
     * before every upload, so granting location permission alone never starts sharing location.
     * This runs while the Wear app is open; background tracking requires a separate foreground
     * service and user-visible notification policy.
     */
    private fun startLiveLocationTracking() {
        liveLocationTrackingJob?.cancel()
        if (!state.value.locationGranted || state.value.profile == null) return

        liveLocationTrackingJob = viewModelScope.launch {
            while (true) {
                val tracking = try {
                    repository.getLiveLocationTracking()
                } catch (error: WearSessionExpiredException) {
                    handleExpiredSession(error)
                    return@launch
                } catch (_: Exception) {
                    null
                }
                if (tracking?.enabled == true) {
                    val location = (locationClient.getLocation() as? LocationResult.Available)?.snapshot
                    if (location != null) {
                        try {
                            repository.uploadLiveLocation(location)
                        } catch (error: WearSessionExpiredException) {
                            handleExpiredSession(error)
                            return@launch
                        } catch (_: Exception) {
                            // The next configured interval retries transient upload failures.
                        }
                        mutableState.update {
                            it.copy(
                                latestLocation = location,
                                locationMessage = if (location.source.name == "CURRENT") "위치 공유 중" else "최근 위치 공유 중",
                                locationStatus = if (location.source.name == "CURRENT") LocationStatus.CURRENT else LocationStatus.LAST_KNOWN,
                            )
                        }
                    }
                }
                delay((tracking?.intervalSeconds ?: LIVE_LOCATION_STATUS_RETRY_SECONDS).toLong() * 1_000)
            }
        }
    }

    private fun loadSafeZone() = viewModelScope.launch {
        val zone = try {
            repository.getSafeZone()
        } catch (error: WearSessionExpiredException) {
            handleExpiredSession(error)
            return@launch
        } catch (_: Exception) {
            null
        }
        updateSafeZone(zone)
        startSafeZoneMonitoring()
    }

    /**
     * Foreground-only automatic departure detection. A location is sampled every 10 seconds;
     * SafeZoneEvaluator confirms only after two outside samples spanning at least 10 seconds.
     * A confirmed departure is latched until the watch returns inside, preventing duplicate
     * server events for the same trip outside the zone.
     */
    private fun startSafeZoneMonitoring() {
        safeZoneMonitoringJob?.cancel()
        deviceStatusJob?.cancel()
        if (!state.value.locationGranted || state.value.profile == null) return

        safeZoneMonitoringJob = viewModelScope.launch {
            while (true) {
                val zone = try {
                    repository.getSafeZone()
                } catch (error: WearSessionExpiredException) {
                    handleExpiredSession(error)
                    return@launch
                } catch (_: Exception) {
                    null
                }
                updateSafeZone(zone)
                if (zone?.enabled == true) {
                    when (val locationResult = locationClient.getLocation()) {
                        is LocationResult.Available -> evaluateSafeZone(zone, locationResult.snapshot)
                        LocationResult.GpsDisabled -> mutableState.update { it.copy(locationStatus = LocationStatus.GPS_DISABLED, locationMessage = "GPS를 사용할 수 없어요") }
                        LocationResult.Unavailable -> Unit // A failed sample must not be treated as leaving the zone.
                    }
                }
                delay(SAFE_ZONE_SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    private fun updateSafeZone(zone: SafeZone?) {
        val key = zone?.takeIf { it.enabled }?.let { "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}" }
        if (key != monitoredZoneKey) {
            monitoredZoneKey = key
            departureConfirmed = false
            safeZoneEvaluator.reset()
            mutableState.update { it.copy(safeZoneEvent = null, safeZoneStatus = SafeZoneStatus.UNKNOWN) }
        }
        mutableState.update { it.copy(safeZone = zone) }
    }

    private fun evaluateSafeZone(zone: SafeZone, location: LocationSnapshot) {
        // Accuracy wider than the smallest supported zone cannot produce a reliable departure.
        if (location.accuracyMeters > MAX_SAFE_ZONE_ACCURACY_METERS) return
        val status = safeZoneEvaluator.evaluate(zone, location)
        mutableState.update {
            it.copy(
                latestLocation = location,
                locationStatus = if (location.source.name == "CURRENT") LocationStatus.CURRENT else LocationStatus.LAST_KNOWN,
                safeZoneStatus = status,
            )
        }
        if (status == SafeZoneStatus.INSIDE) {
            departureConfirmed = false
            return
        }
        if (status != SafeZoneStatus.OUTSIDE_CONFIRMED || departureConfirmed) return

        departureConfirmed = true
        viewModelScope.launch {
            runCatching { repository.createSafeZoneEvent(SafeZoneStatus.OUTSIDE_CONFIRMED, location) }
                .onSuccess { event ->
                    mutableState.update { it.copy(screen = WearScreen.SAFE_ZONE_EXIT, safeZoneEvent = event) }
                    startSafeZoneResponseTimeout(event)
                }
                .onFailure { error ->
                    if (error is WearSessionExpiredException) {
                        handleExpiredSession(error)
                        return@onFailure
                    }
                    // Permit another confirmed sampling cycle after a transport failure.
                    departureConfirmed = false
                    mutableState.update { it.copy(actionError = error.message ?: "안심 구역 이탈을 알리지 못했어요.") }
                }
        }
    }

    private fun startSafeZoneResponseTimeout(event: SafeZoneEvent) {
        safeZoneResponseTimeoutJob?.cancel()
        deviceStatusJob?.cancel()
        safeZoneResponseTimeoutJob = viewModelScope.launch {
            val delayMillis = event.responseDeadlineAt?.let { deadline ->
                Duration.between(java.time.Instant.now(), deadline).toMillis().coerceAtLeast(0L)
            } ?: SAFE_ZONE_RESPONSE_TIMEOUT_MILLIS
            delay(delayMillis)
            if (state.value.screen == WearScreen.SAFE_ZONE_EXIT && state.value.safeZoneEvent?.id == event.id) {
                try {
                    repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.NO_RESPONSE)
                } catch (error: WearSessionExpiredException) {
                    handleExpiredSession(error)
                    return@launch
                } catch (_: Exception) {
                    // The event remains on the server; UI state still returns home on timeout.
                }
                mutableState.update { it.copy(screen = WearScreen.HOME, safeZoneStatus = SafeZoneStatus.NO_RESPONSE) }
            }
        }
    }

    /** Restores a pending server event after the watch process is recreated; server owns the deadline. */
    private fun restoreActiveSafeZoneEvent() = viewModelScope.launch {
        try {
            val event = repository.getActiveSafeZoneEvent() ?: return@launch
            mutableState.update { it.copy(screen = WearScreen.SAFE_ZONE_EXIT, safeZoneEvent = event, safeZoneStatus = event.status) }
            startSafeZoneResponseTimeout(event)
        } catch (error: WearSessionExpiredException) {
            handleExpiredSession(error)
        } catch (_: Exception) {
            // Safe-zone monitoring itself remains available; restoration can retry on the next launch.
        }
    }

    fun confirmSafeZoneOkay() {
        val event = state.value.safeZoneEvent
        if (event != null) viewModelScope.launch {
            try {
                repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.USER_OKAY)
            } catch (error: WearSessionExpiredException) {
                handleExpiredSession(error)
            } catch (_: Exception) {
                mutableState.update { it.copy(actionError = "응답을 보내지 못했어요. 다시 시도해주세요.") }
            }
        }
        safeZoneResponseTimeoutJob?.cancel()
        mutableState.update { it.copy(screen = WearScreen.HOME, safeZoneStatus = SafeZoneStatus.USER_OKAY) }
    }

    fun requestHelpFromSafeZone() {
        state.value.safeZoneEvent?.let { event -> viewModelScope.launch {
            try {
                repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.NEED_HELP)
            } catch (error: WearSessionExpiredException) {
                handleExpiredSession(error)
                return@launch
            } catch (_: Exception) {
                mutableState.update { it.copy(actionError = "응답을 보내지 못했어요. 긴급 도움 요청은 계속 시도할 수 있어요.") }
            }
        } }
        safeZoneResponseTimeoutJob?.cancel()
        createEmergency(EmergencyTrigger.MANUAL_SOS, state.value.latestReading?.bpm)
    }

    fun requestHelpFromHeartRate() {
        heartRateCheckInTimeoutJob?.cancel()
        val reading = pendingHeartRateCheckInReading ?: state.value.latestReading
        pendingHeartRateCheckInReading = null
        createEmergency(EmergencyTrigger.HEART_RATE_CHECK_IN, reading?.bpm)
    }

    fun requestManualSos() {
        createEmergency(EmergencyTrigger.MANUAL_SOS, state.value.latestReading?.bpm)
    }

    private fun createEmergency(
        trigger: EmergencyTrigger,
        heartRateBpm: Int?,
        failureScreen: WearScreen = state.value.screen,
    ) {
        mutableState.update { it.copy(actionError = null, screen = WearScreen.WAITING) }
        viewModelScope.launch {
            runCatching { repository.createEmergency(trigger, heartRateBpm, state.value.latestLocation, state.value.locationStatus) }
                .onSuccess { event ->
                    if (trigger == EmergencyTrigger.HEART_RATE_CHECK_IN) suppressAutomaticHeartRateCheckIns()
                    mutableState.update { it.copy(emergency = event, screen = WearScreen.WAITING) }
                    pollEmergency(event.id)
                }
                .onFailure { error ->
                    if (error is WearSessionExpiredException) {
                        handleExpiredSession(error)
                        return@onFailure
                    }
                    mutableState.update {
                        it.copy(
                            actionError = error.message ?: "도움 요청을 보내지 못했어요.",
                            screen = failureScreen,
                        )
                    }
                }
        }
    }

    private fun pollEmergency(eventId: String) {
        emergencyPollingJob?.cancel()
        emergencyPollingJob = viewModelScope.launch {
            var retryDelayMillis = EMERGENCY_POLL_INTERVAL_MILLIS
            while (true) {
                delay(retryDelayMillis)
                val event = try {
                    repository.getEmergency(eventId)
                } catch (error: WearSessionExpiredException) {
                    handleExpiredSession(error)
                    return@launch
                } catch (_: Exception) {
                    // Keep the pending SOS visible and retry when connectivity returns.
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_EMERGENCY_POLL_RETRY_MILLIS)
                    continue
                }
                retryDelayMillis = EMERGENCY_POLL_INTERVAL_MILLIS
                mutableState.update { it.copy(emergency = event) }

                if (event.status == EmergencyStatus.ACKNOWLEDGED) {
                    mutableState.update { it.copy(screen = WearScreen.ACKNOWLEDGED) }
                    return@launch
                }
            }
        }
    }

    private fun handleExpiredSession(error: WearSessionExpiredException) {
        emergencyPollingJob?.cancel()
        liveLocationTrackingJob?.cancel()
        safeZoneMonitoringJob?.cancel()
        clearLocalConnection(error.message ?: "워치 연결이 만료됐어요.\n새 연결 코드를 입력해주세요.")
    }

    private fun clearLocalConnection(message: String) {
        emergencyPollingJob?.cancel()
        automaticHeartRateJob?.cancel()
        liveLocationTrackingJob?.cancel()
        safeZoneMonitoringJob?.cancel()
        safeZoneResponseTimeoutJob?.cancel()
        heartRateCheckInTimeoutJob?.cancel()
        deviceStatusJob?.cancel()
        pendingHeartRateCheckInReading = null
        automaticHeartRateCheckInsSuppressedUntil = null
        repository.clearSession()
        mutableState.update {
            it.copy(
                screen = WearScreen.PAIRING,
                profile = null,
                pairingCode = "",
                pairingError = message,
                emergency = null,
                safeZone = null,
                safeZoneEvent = null,
                connectionInfo = null,
                isLoadingConnection = false,
                isDisconnecting = false,
                nextAutomaticHeartRateAt = null,
            )
        }
    }

    fun returnHome() {
        emergencyPollingJob?.cancel()
        heartRateCheckInTimeoutJob?.cancel()
        pendingHeartRateCheckInReading = null
        mutableState.update {
            it.copy(screen = WearScreen.HOME, assessment = null, emergency = null, actionError = null)
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(pairingError = null, actionError = null) }
    }

    private fun suppressAutomaticHeartRateCheckIns() {
        automaticHeartRateCheckInsSuppressedUntil = Instant.now().plusMillis(HEART_RATE_CHECK_IN_COOLDOWN_MILLIS)
    }

    override fun onCleared() {
        emergencyPollingJob?.cancel()
        heartRateTimeoutJob?.cancel()
        heartRateCheckInTimeoutJob?.cancel()
        liveLocationTrackingJob?.cancel()
        safeZoneMonitoringJob?.cancel()
        safeZoneResponseTimeoutJob?.cancel()
        heartRateSensorClient?.cancelReading()
        super.onCleared()
    }

    private companion object {
        const val AUTOMATIC_HEART_RATE_INTERVAL_MILLIS = 10_000L
        const val HEART_RATE_CHECK_IN_COOLDOWN_MILLIS = 60_000L
        const val HEART_RATE_CHECK_IN_TIMEOUT_MILLIS = 30_000L
        const val SAFE_ZONE_RESPONSE_TIMEOUT_MILLIS = 30_000L
        const val SAFE_ZONE_SAMPLE_INTERVAL_MILLIS = 10_000L
        const val MAX_SAFE_ZONE_ACCURACY_METERS = 100f
        const val LIVE_LOCATION_STATUS_RETRY_SECONDS = 10
        const val EMERGENCY_POLL_INTERVAL_MILLIS = 1_000L
        const val MAX_EMERGENCY_POLL_RETRY_MILLIS = 16_000L
        const val DEVICE_STATUS_REPORT_INTERVAL_MILLIS = 15 * 60_000L
    }
}

class CareOnWearViewModelFactory(private val repository: CareOnRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CareOnWearViewModel(repository) as T
}
