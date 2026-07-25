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
}

data class CareOnWearUiState(
    val screen: WearScreen = WearScreen.PAIRING,
    val pairingCode: String = "",
    val pairingError: String? = null,
    val isPairing: Boolean = false,
    val profile: WearProfile? = null,
    val isMeasuring: Boolean = false,
    val heartRatePermissionGranted: Boolean = false,
    val requestHeartRatePermission: Boolean = false,
    val latestReading: HeartRateReading? = null,
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
    private var locationClient: CareOnLocationClient = DemoLocationClient()
    private var heartRateSensorClient: HeartRateSensorClient? = null
    private var heartRateTimeoutJob: Job? = null
    private val safeZoneEvaluator = SafeZoneEvaluator()

    init {
        restoreSession()
    }

    fun setLocationClient(client: CareOnLocationClient) { locationClient = client }
    fun setHeartRateSensorClient(client: HeartRateSensorClient) { heartRateSensorClient = client }

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
        } else {
            liveLocationTrackingJob?.cancel()
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
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isPairing = false, pairingError = error.message ?: "연결하지 못했어요.")
                    }
                }
        }
    }

    fun measureHeartRate() {
        val profile = state.value.profile ?: return
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
            onReading = { bpm -> onHeartRateReading(bpm, profile) },
            onError = ::onHeartRateMeasurementError,
        )
        heartRateTimeoutJob?.cancel()
        heartRateTimeoutJob = viewModelScope.launch {
            delay(20_000)
            if (state.value.isMeasuring) onHeartRateMeasurementError("심박수를 측정하지 못했어요. 워치를 손목에 착용한 뒤 다시 시도해주세요.")
        }
    }

    private fun onHeartRateReading(bpm: Int, profile: WearProfile) {
        heartRateTimeoutJob?.cancel()
        val reading = HeartRateReading(bpm = bpm, measuredAt = java.time.Instant.now(), source = "WATCH_SENSOR")
        val assessment = assessHeartRate(reading.bpm, profile.heartRateCheckInThreshold)
        mutableState.update {
            it.copy(assessment = assessment, isMeasuring = false, latestReading = reading, screen = WearScreen.RESULT)
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
        if (!state.value.heartRatePermissionGranted || heartRateSensorClient == null) return

        automaticHeartRateJob = viewModelScope.launch {
            while (true) {
                delay(AUTOMATIC_HEART_RATE_INTERVAL_MILLIS)
                val profile = state.value.profile ?: continue
                val sensor = heartRateSensorClient ?: continue
                sensor.requestReading(
                    onReading = { bpm -> onAutomaticHeartRateReading(bpm, profile) },
                    // A sensor can legitimately have no fresh value in this interval. Keep the
                    // previous reading and try again next cycle without interrupting the user.
                    onError = {},
                )
            }
        }
    }

    private fun onAutomaticHeartRateReading(bpm: Int, profile: WearProfile) {
        val reading = HeartRateReading(bpm = bpm, measuredAt = java.time.Instant.now(), source = "WATCH_SENSOR")
        val assessment = assessHeartRate(reading.bpm, profile.heartRateCheckInThreshold)
        mutableState.update { current ->
            current.copy(
                latestReading = reading,
                assessment = assessment,
                screen = if (assessment == HeartRateAssessment.CHECK_IN && current.screen == WearScreen.HOME) WearScreen.CHECK_IN else current.screen,
            )
        }
    }

    fun openCheckIn() = mutableState.update { it.copy(screen = WearScreen.CHECK_IN) }

    fun sayOkay() = mutableState.update {
        it.copy(screen = WearScreen.HOME, assessment = null, actionError = null)
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
                val tracking = runCatching { repository.getLiveLocationTracking() }.getOrNull()
                if (tracking?.enabled == true) {
                    val location = (locationClient.getLocation() as? LocationResult.Available)?.snapshot
                    if (location != null) {
                        runCatching { repository.uploadLiveLocation(location) }
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
        mutableState.update { it.copy(safeZone = repository.getSafeZone()) }
    }

    /** Enables repeatable emulator demos without creating a production background tracker. */
    fun showDemoSafeZoneExit() {
        val location = state.value.latestLocation ?: return
        mutableState.update { it.copy(screen = WearScreen.SAFE_ZONE_EXIT, safeZoneStatus = safeZoneEvaluator.forceDemoOutside()) }
        viewModelScope.launch {
            runCatching { repository.createSafeZoneEvent(SafeZoneStatus.OUTSIDE_CONFIRMED, location) }
                .onSuccess { event -> mutableState.update { it.copy(safeZoneEvent = event) } }
                .onFailure { error -> mutableState.update { it.copy(actionError = error.message) } }
        }
        viewModelScope.launch {
            delay(SAFE_ZONE_RESPONSE_TIMEOUT_MILLIS)
            if (state.value.screen == WearScreen.SAFE_ZONE_EXIT) {
                state.value.safeZoneEvent?.let { event -> runCatching { repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.NO_RESPONSE) } }
                mutableState.update { it.copy(screen = WearScreen.HOME, safeZoneStatus = SafeZoneStatus.NO_RESPONSE) }
            }
        }
    }

    fun confirmSafeZoneOkay() {
        val event = state.value.safeZoneEvent
        if (event != null) viewModelScope.launch { runCatching { repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.USER_OKAY) } }
        mutableState.update { it.copy(screen = WearScreen.HOME, safeZoneStatus = SafeZoneStatus.USER_OKAY) }
    }

    fun requestHelpFromSafeZone() {
        state.value.safeZoneEvent?.let { event -> viewModelScope.launch { runCatching { repository.respondToSafeZoneEvent(event.id, SafeZoneStatus.NEED_HELP) } } }
        createEmergency(EmergencyTrigger.MANUAL_SOS, state.value.latestReading?.bpm)
    }

    fun requestHelpFromHeartRate() {
        createEmergency(EmergencyTrigger.HEART_RATE_CHECK_IN, state.value.latestReading?.bpm)
    }

    fun requestManualSos() {
        createEmergency(EmergencyTrigger.MANUAL_SOS, state.value.latestReading?.bpm)
    }

    private fun createEmergency(trigger: EmergencyTrigger, heartRateBpm: Int?) {
        mutableState.update { it.copy(actionError = null) }
        viewModelScope.launch {
            runCatching { repository.createEmergency(trigger, heartRateBpm, state.value.latestLocation, state.value.locationStatus) }
                .onSuccess { event ->
                    mutableState.update { it.copy(emergency = event, screen = WearScreen.WAITING) }
                    pollEmergency(event.id)
                }
                .onFailure { error ->
                    mutableState.update { it.copy(actionError = error.message ?: "도움 요청을 보내지 못했어요.") }
                }
        }
    }

    private fun pollEmergency(eventId: String) {
        emergencyPollingJob?.cancel()
        automaticHeartRateJob?.cancel()
        emergencyPollingJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val event = repository.getEmergency(eventId)
                mutableState.update { it.copy(emergency = event) }

                if (event.status == EmergencyStatus.ACKNOWLEDGED) {
                    mutableState.update { it.copy(screen = WearScreen.ACKNOWLEDGED) }
                    return@launch
                }
            }
        }
    }

    fun returnHome() {
        emergencyPollingJob?.cancel()
        mutableState.update {
            it.copy(screen = WearScreen.HOME, assessment = null, emergency = null, actionError = null)
        }
    }

    override fun onCleared() {
        emergencyPollingJob?.cancel()
        heartRateTimeoutJob?.cancel()
        liveLocationTrackingJob?.cancel()
        heartRateSensorClient?.cancelReading()
        super.onCleared()
    }

    private companion object {
        const val AUTOMATIC_HEART_RATE_INTERVAL_MILLIS = 30_000L
        const val SAFE_ZONE_RESPONSE_TIMEOUT_MILLIS = 30_000L
        const val LIVE_LOCATION_STATUS_RETRY_SECONDS = 10
    }
}

class CareOnWearViewModelFactory(private val repository: CareOnRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CareOnWearViewModel(repository) as T
}
