package com.careon.wear.ui

import androidx.lifecycle.ViewModel
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
}

data class CareOnWearUiState(
    val screen: WearScreen = WearScreen.PAIRING,
    val pairingCode: String = "",
    val pairingError: String? = null,
    val isPairing: Boolean = false,
    val profile: WearProfile? = null,
    val demoBpm: Int = 78,
    val isMeasuring: Boolean = false,
    val latestReading: HeartRateReading? = null,
    val assessment: HeartRateAssessment? = null,
    val emergency: EmergencyEvent? = null,
    val actionError: String? = null,
)

class CareOnWearViewModel(
    private val repository: CareOnRepository = DemoCareOnRepository(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareOnWearUiState())
    val state: StateFlow<CareOnWearUiState> = mutableState.asStateFlow()
    private var emergencyPollingJob: Job? = null
    private var automaticHeartRateJob: Job? = null

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
                            screen = WearScreen.HOME,
                        )
                    }
                    startAutomaticHeartRateMeasurement()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isPairing = false, pairingError = error.message ?: "연결하지 못했어요.")
                    }
                }
        }
    }

    fun toggleDemoHeartRate() {
        mutableState.update { current ->
            current.copy(demoBpm = if (current.demoBpm >= 110) 78 else 124)
        }
    }

    fun measureHeartRate() {
        val profile = state.value.profile ?: return
        val bpm = state.value.demoBpm
        mutableState.update { it.copy(isMeasuring = true, screen = WearScreen.MEASURING, actionError = null) }

        viewModelScope.launch {
            runCatching { repository.measureHeartRate(bpm) }
                .onSuccess { reading ->
                    val assessment = assessHeartRate(reading.bpm, profile.heartRateCheckInThreshold)
                    mutableState.update {
                        it.copy(
                            assessment = assessment,
                            isMeasuring = false,
                            latestReading = reading,
                            screen = WearScreen.RESULT,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isMeasuring = false, screen = WearScreen.HOME, actionError = error.message)
                    }
                }
        }
    }

    /**
     * Demo-only 30-second cadence. The API implementation can later replace this with
     * Health Services / sensor readings while preserving the same UI state contract.
     */
    private fun startAutomaticHeartRateMeasurement() {
        automaticHeartRateJob?.cancel()
        automaticHeartRateJob = viewModelScope.launch {
            while (true) {
                delay(AUTOMATIC_HEART_RATE_INTERVAL_MILLIS)
                val current = state.value
                val profile = current.profile ?: continue
                val reading = runCatching { repository.measureHeartRate(current.demoBpm) }.getOrNull() ?: continue
                val assessment = assessHeartRate(reading.bpm, profile.heartRateCheckInThreshold)

                mutableState.update { latest ->
                    latest.copy(
                        latestReading = reading,
                        assessment = assessment,
                        screen = if (assessment == HeartRateAssessment.CHECK_IN && latest.screen == WearScreen.HOME) {
                            WearScreen.CHECK_IN
                        } else {
                            latest.screen
                        },
                    )
                }
            }
        }
    }

    fun openCheckIn() = mutableState.update { it.copy(screen = WearScreen.CHECK_IN) }

    fun sayOkay() = mutableState.update {
        it.copy(screen = WearScreen.HOME, assessment = null, actionError = null)
    }

    fun openSos() = mutableState.update { it.copy(screen = WearScreen.SOS, actionError = null) }

    fun requestHelpFromHeartRate() {
        createEmergency(EmergencyTrigger.HEART_RATE_CHECK_IN, state.value.latestReading?.bpm)
    }

    fun requestManualSos() {
        createEmergency(EmergencyTrigger.MANUAL_SOS, state.value.latestReading?.bpm)
    }

    private fun createEmergency(trigger: EmergencyTrigger, heartRateBpm: Int?) {
        mutableState.update { it.copy(actionError = null) }
        viewModelScope.launch {
            runCatching { repository.createEmergency(trigger, heartRateBpm) }
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
        automaticHeartRateJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val AUTOMATIC_HEART_RATE_INTERVAL_MILLIS = 30_000L
    }
}
