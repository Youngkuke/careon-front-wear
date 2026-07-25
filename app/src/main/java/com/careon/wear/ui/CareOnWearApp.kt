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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import com.careon.wear.data.DemoCareOnRepository
import com.careon.wear.data.EmergencyStatus
import com.careon.wear.data.HeartRateAssessment
import com.careon.wear.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val WEAR_BASE_WIDTH_DP = 192f

private data class WearLayoutScale(val multiplier: Float) {
    fun size(base: Dp): Dp = base * multiplier
}

private val LocalWearLayoutScale = staticCompositionLocalOf { WearLayoutScale(1f) }

@Composable
fun CareOnWearApp(viewModel: CareOnWearViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        if (showLaunchLogo) {
            LaunchLogoScreen()
        } else if (state.screen == WearScreen.PAIRING) {
            PairingScreen(
                code = state.pairingCode,
                error = state.pairingError,
                isPairing = state.isPairing,
                onDigit = viewModel::appendPairingDigit,
                onDelete = viewModel::removePairingDigit,
                onPair = viewModel::pair,
            )
        } else {
            // A new destination owns a new scroll state, so it always opens at the top.
            key(state.screen) {
                if (state.screen == WearScreen.SOS) {
                    SosScreen(error = state.actionError, onConfirmed = viewModel::requestManualSos)
                } else if (state.screen == WearScreen.WAITING) {
                    WaitingScreen(status = state.emergency?.status ?: EmergencyStatus.PENDING)
                } else if (state.screen == WearScreen.ACKNOWLEDGED) {
                    AcknowledgedScreen(onReturnHome = viewModel::returnHome)
                } else WearPage(centerContent = true) {
                    when (state.screen) {
                WearScreen.PAIRING -> Unit

                WearScreen.HOME -> HomeScreen(
                    latestBpm = state.latestReading?.bpm,
                    demoBpm = state.demoBpm,
                    error = state.actionError,
                    onToggleDemoHeartRate = viewModel::toggleDemoHeartRate,
                    onMeasure = viewModel::measureHeartRate,
                    onOpenSos = viewModel::openSos,
                )

                WearScreen.MEASURING -> MeasuringScreen()
                WearScreen.RESULT -> ResultScreen(
                    bpm = state.latestReading?.bpm ?: state.demoBpm,
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
                    }
                }
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
        Text(text, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = CareOnWearColors.PrimaryDark)
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
private fun PairingScreen(code: String, error: String?, isPairing: Boolean, onDigit: (String) -> Unit, onDelete: () -> Unit, onPair: () -> Unit) {
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
            Text("DEMO: ${DemoCareOnRepository.DEMO_PAIRING_CODE}", color = CareOnWearColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (error != null) {
            Spacer(Modifier.height(scale.size(4.dp)))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
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
private fun HomeScreen(latestBpm: Int?, demoBpm: Int, error: String?, onToggleDemoHeartRate: () -> Unit, onMeasure: () -> Unit, onOpenSos: () -> Unit) {
    val scale = LocalWearLayoutScale.current
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
    }
    Spacer(Modifier.height(scale.size(9.dp)))
    PrimaryButton("심박수 확인", onClick = onMeasure)
    Spacer(Modifier.height(scale.size(7.dp)))
    DangerButton("긴급 도움", onClick = onOpenSos)
    Spacer(Modifier.height(scale.size(8.dp)))
    OutlinedButton(onClick = onToggleDemoHeartRate, modifier = Modifier.height(scale.size(30.dp)), border = BorderStroke(scale.size(1.dp), CareOnWearColors.Line)) {
        Text("다음 데모값 $demoBpm", style = MaterialTheme.typography.labelSmall, color = CareOnWearColors.Text)
    }
    if (error != null) {
        Spacer(Modifier.height(scale.size(5.dp)))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
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
        color = CareOnWearColors.PrimaryDark,
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
private fun SosScreen(error: String?, onConfirmed: () -> Unit) {
    val scale = LocalWearLayoutScale.current
    var isPressing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPressing) {
        if (!isPressing) { progress = 0f; return@LaunchedEffect }
        repeat(30) { index -> delay(100); progress = (index + 1) / 30f }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
            detectTapGestures(onPress = {
                isPressing = true
                val releasedEarly = withTimeoutOrNull(3_000) { tryAwaitRelease(); true } ?: false
                isPressing = false
                if (!releasedEarly) { onConfirmed(); tryAwaitRelease() }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = scale.size(27.dp), vertical = scale.size(14.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PageTitle("긴급 도움 요청")
            Spacer(Modifier.height(scale.size(5.dp)))
            Text(
                "화면을 3초동안\n길게 눌러주세요",
                color = CareOnWearColors.Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(scale.size(10.dp)))
            DemoProgress(modifier = Modifier.fillMaxWidth(), progress = progress, color = CareOnWearColors.Danger)
            if (isPressing) {
                Spacer(Modifier.height(scale.size(7.dp)))
                Text("${(progress * 3).toInt() + 1}초 유지", color = CareOnWearColors.Danger, fontWeight = FontWeight.Bold)
            }
            if (error != null) {
                Spacer(Modifier.height(scale.size(8.dp)))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
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
            DemoProgress(modifier = Modifier.fillMaxWidth())
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
