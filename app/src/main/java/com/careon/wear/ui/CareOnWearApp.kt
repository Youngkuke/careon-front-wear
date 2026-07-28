package com.careon.wear.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.BatteryManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import com.careon.wear.data.CareOnRepository
import com.careon.wear.data.EmergencyStatus
import com.careon.wear.data.HeartRateAssessment
import com.careon.wear.location.FusedCareOnLocationClient
import com.careon.wear.sensor.AndroidHeartRateSensorClient
import com.careon.wear.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.math.ceil

private const val WEAR_BASE_WIDTH_DP = 192f

private data class WearLayoutScale(val multiplier: Float) {
    fun size(base: Dp): Dp = base * multiplier
}

private val LocalWearLayoutScale = staticCompositionLocalOf { WearLayoutScale(1f) }

@Composable
fun CareOnWearApp(repository: CareOnRepository? = null) {
    val viewModel: CareOnWearViewModel = if (repository == null) viewModel() else viewModel(factory = CareOnWearViewModelFactory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        viewModel.onLocationPermission(permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }
    val heartRatePermission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
    val heartRatePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onHeartRatePermission(granted)
    }
    LaunchedEffect(context) {
        viewModel.setLocationClient(FusedCareOnLocationClient(context))
        viewModel.setHeartRateSensorClient(AndroidHeartRateSensorClient(context))
        viewModel.setBatteryPercentProvider {
            (context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                ?.takeIf { it in 0..100 }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) viewModel.onLocationPermission(true)
        if (ContextCompat.checkSelfPermission(context, heartRatePermission) == PackageManager.PERMISSION_GRANTED) viewModel.onHeartRatePermission(true)
    }
    LaunchedEffect(state.requestHeartRatePermission) {
        if (state.requestHeartRatePermission) heartRatePermissionLauncher.launch(heartRatePermission)
    }
    var showLaunchLogo by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1_100)
        showLaunchLogo = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutScale = WearLayoutScale(
            multiplier = (maxWidth.value / WEAR_BASE_WIDTH_DP).coerceIn(0.82f, 1.25f),
        )
        CompositionLocalProvider(LocalWearLayoutScale provides layoutScale) {
            MaterialTheme(colorScheme = CareOnWearColorScheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showLaunchLogo) {
                        LaunchLogoScreen()
                    } else if (state.screen == WearScreen.PAIRING) {
                        PairingScreen(
                            code = state.pairingCode,
                            isPairing = state.isPairing,
                            onDigit = viewModel::appendPairingDigit,
                            onDelete = viewModel::removePairingDigit,
                            onPair = viewModel::pair,
                        )
                    } else {
                        // A new destination owns a new scroll state, so it always opens at the top.
                        key(state.screen) {
                            if (state.screen == WearScreen.SOS) {
                                SosScreen(locationMessage = state.locationMessage, isFetchingLocation = state.isFetchingLocation, onEnter = viewModel::prepareSosLocation, onConfirmed = viewModel::requestManualSos, onHome = viewModel::returnHome)
                            } else if (state.screen == WearScreen.WAITING) {
                                WaitingScreen(status = state.emergency?.status ?: EmergencyStatus.PENDING)
                            } else if (state.screen == WearScreen.ACKNOWLEDGED) {
                                AcknowledgedScreen(onReturnHome = viewModel::returnHome)
                            } else WearPage(centerContent = true) {
                                when (state.screen) {
                WearScreen.PAIRING -> Unit

                WearScreen.HOME -> HomeScreen(
                    latestBpm = state.latestReading?.bpm,
                    nextAutomaticHeartRateAt = state.nextAutomaticHeartRateAt,
                    onMeasure = viewModel::measureHeartRate,
                    onOpenSos = viewModel::openSos,
                    onOpenSettings = viewModel::openSettings,
                )

                WearScreen.MEASURING -> MeasuringScreen()
                WearScreen.RESULT -> ResultScreen(
                    bpm = state.latestReading?.bpm ?: 0,
                    assessment = state.assessment ?: HeartRateAssessment.NORMAL,
                    onOkay = viewModel::sayOkay,
                    onOpenCheckIn = viewModel::openCheckIn,
                )

                WearScreen.CHECK_IN -> CheckInScreen(
                    onOkay = viewModel::sayOkay,
                    onNeedHelp = viewModel::requestHelpFromHeartRate,
                )

                WearScreen.SOS -> Unit
                WearScreen.WAITING -> Unit

                WearScreen.ACKNOWLEDGED -> Unit
                WearScreen.LOCATION_PERMISSION -> LocationPermissionScreen(
                    onAllow = { locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    onSkip = { viewModel.onLocationPermission(false) },
                )
                WearScreen.SAFE_ZONE_EXIT -> SafeZoneExitScreen(onOkay = viewModel::confirmSafeZoneOkay, onNeedHelp = viewModel::requestHelpFromSafeZone)
                WearScreen.SETTINGS -> ConnectionSettingsScreen(
                    connectionInfo = state.connectionInfo,
                    isLoading = state.isLoadingConnection,
                    isDisconnecting = state.isDisconnecting,
                    onDisconnect = viewModel::disconnectWear,
                    onBack = viewModel::returnHome,
                )
	                    }
	                }
	            }
	                    }
	                    if (!showLaunchLogo) {
                        ErrorOverlay(
                            message = if (state.screen == WearScreen.PAIRING) state.pairingError else state.actionError,
                            onDismiss = viewModel::dismissError,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WearPage(centerContent: Boolean, content: @Composable () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            // The scroll modifier must wrap the padded content, otherwise lower controls are clipped.
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = scale.size(27.dp), vertical = scale.size(14.dp)),
            contentAlignment = if (centerContent) Alignment.Center else Alignment.TopCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun LaunchLogoScreen() {
    val scale = LocalWearLayoutScale.current
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.careon_logo),
            contentDescription = "CareOn",
            modifier = Modifier.width(scale.size(116.dp)).height(scale.size(44.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun PageTitle(title: String, description: String? = null) {
    val scale = LocalWearLayoutScale.current
    Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    if (description != null) {
        Spacer(Modifier.height(scale.size(5.dp)))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Button(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth(0.76f).height(scale.size(42.dp))) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun SoftButton(text: String, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(0.76f).height(scale.size(42.dp))) {
        Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = CareOnWearColors.PrimaryDark)
    }
}

@Composable
private fun GrayButton(text: String, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.76f).height(scale.size(42.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = CareOnWearColors.Muted, contentColor = Color.White),
    ) {
        Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun DangerButton(text: String, tall: Boolean = false, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.76f).height(scale.size(if (tall) 54.dp else 42.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = CareOnWearColors.Danger, contentColor = Color.White),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun DemoProgress(modifier: Modifier = Modifier, progress: Float = 0.55f, color: Color = CareOnWearColors.Primary) {
    val scale = LocalWearLayoutScale.current
    Box(modifier = modifier.height(scale.size(6.dp)).clip(CircleShape).background(CareOnWearColors.Line)) {
        Box(modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(scale.size(6.dp)).clip(CircleShape).background(color))
    }
}

@Composable
private fun PairingScreen(code: String, isPairing: Boolean, onDigit: (String) -> Unit, onDelete: () -> Unit, onPair: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = scale.size(27.dp), vertical = scale.size(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "워치 연결",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = CareOnWearColors.Text,
        )
        Spacer(Modifier.height(scale.size(4.dp)))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(scale.size(19.dp))).background(CareOnWearColors.Surface)
                .padding(horizontal = scale.size(12.dp), vertical = scale.size(6.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(code.padEnd(6, '•').chunked(3).joinToString("  "), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CareOnWearColors.PrimaryDark)
        }
        Spacer(Modifier.height(scale.size(3.dp)))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(scale.size(7.dp)))
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                KeypadRow { row.forEach { digit -> KeyButton(digit) { onDigit(digit) } } }
                Spacer(Modifier.height(scale.size(7.dp)))
            }
            KeypadRow {
                KeyButton("←", onDelete)
                KeyButton("0") { onDigit("0") }
                ConnectKeyButton(
                    enabled = code.length == 6 && !isPairing,
                    isPairing = isPairing,
                    onClick = onPair,
                )
            }
            Spacer(Modifier.height(scale.size(16.dp)))
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable RowScope.() -> Unit) {
    val scale = LocalWearLayoutScale.current
    Row(
        // Every keypad control is square, producing a consistent circular touch target.
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(scale.size(7.dp)),
        content = content,
    )
}

@Composable
private fun RowScope.KeyButton(label: String, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(scale.size(42.dp)),
        border = BorderStroke(scale.size(1.dp), CareOnWearColors.Line),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = CareOnWearColors.Text, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun RowScope.ConnectKeyButton(enabled: Boolean, isPairing: Boolean, onClick: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(scale.size(42.dp)),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (isPairing) "…" else "✓",
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HomeScreen(latestBpm: Int?, nextAutomaticHeartRateAt: Instant?, onMeasure: () -> Unit, onOpenSos: () -> Unit, onOpenSettings: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    var currentTime by remember(nextAutomaticHeartRateAt) { mutableStateOf(Instant.now()) }
    LaunchedEffect(nextAutomaticHeartRateAt) {
        while (nextAutomaticHeartRateAt != null) {
            currentTime = Instant.now()
            delay(250)
        }
    }
    val remainingSeconds = nextAutomaticHeartRateAt?.let { target ->
        ceil((target.toEpochMilli() - currentTime.toEpochMilli()).coerceAtLeast(0L) / 1_000.0).toInt().coerceAtLeast(1)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("최근 심박수", style = MaterialTheme.typography.labelSmall, color = CareOnWearColors.Muted)
        Spacer(Modifier.height(scale.size(1.dp)))
        Text(latestBpm?.toString() ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = CareOnWearColors.PrimaryDark)
        Text(
            if (latestBpm == null) "아직 측정하지 않았어요" else "BPM",
            color = CareOnWearColors.Muted,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            if (remainingSeconds == null) "자동 측정 대기 중" else "${remainingSeconds}초",
            color = CareOnWearColors.Muted,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
    Spacer(Modifier.height(scale.size(9.dp)))
    PrimaryButton("심박수 확인", onClick = onMeasure)
    Spacer(Modifier.height(scale.size(7.dp)))
    DangerButton("긴급 도움", onClick = onOpenSos)
    Spacer(Modifier.height(scale.size(7.dp)))
    GrayButton("설정", onClick = onOpenSettings)
}

@Composable
private fun ConnectionSettingsScreen(connectionInfo: com.careon.wear.data.WearConnectionInfo?, isLoading: Boolean, isDisconnecting: Boolean, onDisconnect: () -> Unit, onBack: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    PageTitle("워치 설정")
    Spacer(Modifier.height(scale.size(8.dp)))
    if (isLoading) Text("연결 정보를 불러오는 중…", color = CareOnWearColors.Muted, fontSize = 11.sp)
    else if (connectionInfo != null) {
        Text("연결된 보호자", color = CareOnWearColors.Muted, fontSize = 10.sp)
        Text(connectionInfo.carerName, color = CareOnWearColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        Text(connectionInfo.carerEmail, color = CareOnWearColors.Muted, fontSize = 10.sp, maxLines = 1)
    } else Text("연결된 보호자 정보가 없어요.", color = CareOnWearColors.Muted, fontSize = 11.sp)
    Spacer(Modifier.height(scale.size(11.dp)))
    DangerButton(if (isDisconnecting) "연결 해제 중…" else "연결 해제", onClick = onDisconnect)
    Spacer(Modifier.height(scale.size(7.dp)))
    GrayButton("홈으로", onClick = onBack)
}

@Composable
private fun MeasuringScreen() {
    val scale = LocalWearLayoutScale.current
    val pulse = rememberInfiniteTransition(label = "heartPulse").animateFloat(
        initialValue = 0.82f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heartScale",
    )
    Text(
        "♥",
        modifier = Modifier.graphicsLayer(scaleX = pulse.value, scaleY = pulse.value),
        color = CareOnWearColors.Danger,
        style = MaterialTheme.typography.displayLarge,
    )
    Spacer(Modifier.height(scale.size(13.dp)))
    PageTitle("심박수 확인 중")
    Spacer(Modifier.height(scale.size(5.dp)))
    Text("워치를 편하게 착용해주세요", color = CareOnWearColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ResultScreen(bpm: Int, assessment: HeartRateAssessment, onOkay: () -> Unit, onOpenCheckIn: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    val normal = assessment == HeartRateAssessment.NORMAL
    val accent = if (normal) CareOnWearColors.Text else CareOnWearColors.Warning
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (normal) "측정 완료" else "확인이 필요해요", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
        Text("$bpm", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = accent)
        Text("BPM", style = MaterialTheme.typography.labelSmall, color = CareOnWearColors.Muted)
    }
    Spacer(Modifier.height(scale.size(13.dp)))
    if (normal) {
        Text("측정 결과를 기록했어요", color = CareOnWearColors.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(scale.size(13.dp)))
        PrimaryButton("확인", onClick = onOkay)
    } else {
        Text(
            "심박수가 설정 범위를\n벗어났어요",
            color = CareOnWearColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(scale.size(13.dp)))
        PrimaryButton("상태 확인", onClick = onOpenCheckIn)
    }
}

@Composable
private fun CheckInScreen(onOkay: () -> Unit, onNeedHelp: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    PageTitle("현재 상태를\n알려주세요")
    Spacer(Modifier.height(scale.size(18.dp)))
    PrimaryButton("괜찮아요", onClick = onOkay)
    Spacer(Modifier.height(scale.size(8.dp)))
    DangerButton("도움이\n필요해요", tall = true, onClick = onNeedHelp)
}

@Composable
private fun SosScreen(locationMessage: String, isFetchingLocation: Boolean, onEnter: () -> Unit, onConfirmed: () -> Unit, onHome: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    var isPressing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { onEnter() }
    LaunchedEffect(isPressing) {
        if (!isPressing) { progress = 0f; return@LaunchedEffect }
        repeat(30) { index -> delay(100); progress = (index + 1) / 30f }
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = scale.size(27.dp), vertical = scale.size(14.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PageTitle("긴급 도움 요청")
            Spacer(Modifier.height(scale.size(5.dp)))
            Box(
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        isPressing = true
                        val releasedEarly = withTimeoutOrNull(3_000) { tryAwaitRelease(); true } ?: false
                        isPressing = false
                        if (!releasedEarly) { onConfirmed(); tryAwaitRelease() }
                    })
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "화면을 3초동안\n길게 눌러주세요",
                        color = CareOnWearColors.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(scale.size(10.dp)))
                    DemoProgress(modifier = Modifier.fillMaxWidth(), progress = progress, color = CareOnWearColors.Danger)
                    Spacer(Modifier.height(scale.size(7.dp)))
                    Text(if (isFetchingLocation) "위치 확인 중…" else locationMessage, color = CareOnWearColors.Muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    if (isPressing) {
                        Spacer(Modifier.height(scale.size(7.dp)))
                        Text("${(progress * 3).toInt() + 1}초 유지", color = CareOnWearColors.Danger, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!isPressing) {
                Spacer(Modifier.height(scale.size(9.dp)))
                GrayButton("홈으로", onClick = onHome)
            }
        }
    }
}

@Composable
private fun BoxScope.ErrorOverlay(message: String?, onDismiss: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    if (message == null) return

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = scale.size(18.dp))
            .fillMaxWidth()
            .clip(RoundedCornerShape(scale.size(12.dp)))
            .background(CareOnWearColors.Danger.copy(alpha = 0.88f))
            .padding(horizontal = scale.size(10.dp), vertical = scale.size(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(scale.size(5.dp)))
            Box(
                modifier = Modifier
                    .size(scale.size(24.dp))
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LocationPermissionScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "긴급 도움 요청과\n안심 구역 확인에만 사용해요",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(scale.size(18.dp)))
            PrimaryButton("위치 허용", onClick = onAllow)
            Spacer(Modifier.height(scale.size(8.dp)))
            SoftButton("나중에", onClick = onSkip)
        }
    }
}

@Composable
private fun SafeZoneExitScreen(onOkay: () -> Unit, onNeedHelp: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PageTitle("안심 구역을\n벗어났어요")
        Spacer(Modifier.height(scale.size(18.dp)))
        PrimaryButton("괜찮아요", onClick = onOkay)
        Spacer(Modifier.height(scale.size(8.dp)))
        DangerButton("도움이\n필요해요", tall = true, onClick = onNeedHelp)
    }
}

@Composable
private fun WaitingScreen(status: EmergencyStatus) {
    val scale = LocalWearLayoutScale.current
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = scale.size(27.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageTitle("도움 요청을\n보냈어요")
            Spacer(Modifier.height(scale.size(14.dp)))
            Text(if (status == EmergencyStatus.PENDING) "보호자에게 알림을 보냈어요" else "확인 상태를 불러오는 중이에요", color = CareOnWearColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(scale.size(12.dp)))
            CircularProgressIndicator(
                modifier = Modifier.size(scale.size(30.dp)),
            )
        }
    }
}

@Composable
private fun AcknowledgedScreen(onReturnHome: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = scale.size(27.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "보호자 확인",
                color = CareOnWearColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(scale.size(5.dp)))
            Text("곧 연락드릴 예정이에요", color = CareOnWearColors.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(scale.size(18.dp)))
            PrimaryButton("홈으로", onClick = onReturnHome)
        }
    }
}
