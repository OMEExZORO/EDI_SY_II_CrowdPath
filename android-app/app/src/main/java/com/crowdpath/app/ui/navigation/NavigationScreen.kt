package com.crowdpath.app.ui.navigation

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crowdpath.app.data.database.AppDatabase
import com.crowdpath.app.data.database.CachedMapEntity
import com.crowdpath.app.data.repository.MapRepository
import com.crowdpath.app.navigation.NavigationEngine
import com.crowdpath.app.ble.CaneClient
import com.crowdpath.app.ble.CaneUdpClient
import com.crowdpath.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

// ── Phone haptic patterns ──────────────────────────────────────────────────
//
// These match the smart cane's vibration language so the phone alone
// is sufficient for testing without the hardware cane:
//
//   LEFT TURN   = 1 buzz  (200 ms)        ●
//   RIGHT TURN  = 2 buzzes (200 ms each)  ● ●
//   STOP/ARRIVED= 3 buzzes (200 ms each)  ● ● ●
//   STRAIGHT    = silent   (no buzz)      — keep walking
//   UI TAP      = 1 short buzz (50 ms)    feedback for button taps
//   CONFIRM     = 1 medium buzz (130 ms)  destination confirmed
//   ERROR       = 1 long buzz (400 ms)    something went wrong

private fun vibratePhone(context: Context, type: HapticType) {
    @Suppress("DEPRECATION")
    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    val GAP = 180L   // gap between buzzes (ms)
    val BZZ = 200L   // buzz duration (ms)
    val effect = when (type) {
        // ── Navigation events ─────────────────────────────────────────
        HapticType.LEFT_TURN  ->
            // 1 buzz
            VibrationEffect.createWaveform(
                longArrayOf(0, BZZ), -1
            )
        HapticType.RIGHT_TURN ->
            // 2 buzzes
            VibrationEffect.createWaveform(
                longArrayOf(0, BZZ, GAP, BZZ), -1
            )
        HapticType.ARRIVED    ->
            // 3 buzzes
            VibrationEffect.createWaveform(
                longArrayOf(0, BZZ, GAP, BZZ, GAP, BZZ), -1
            )
        HapticType.STRAIGHT   -> return   // silent — no buzz, just keep walking
        // ── UI feedback ───────────────────────────────────────────────
        HapticType.TAP        ->
            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        HapticType.CONFIRM    ->
            VibrationEffect.createOneShot(130, VibrationEffect.DEFAULT_AMPLITUDE)
        HapticType.ERROR      ->
            VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    vib.vibrate(effect)
}

enum class HapticType {
    LEFT_TURN,   // 1 buzz
    RIGHT_TURN,  // 2 buzzes
    ARRIVED,     // 3 buzzes
    STRAIGHT,    // silent
    TAP,         // short UI tap
    CONFIRM,     // medium UI confirm
    ERROR        // long UI error
}

// ── Main screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen() {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Pick cane transport: WiFi/UDP (ESP8266) or BLE (ESP32) based on Settings toggle
    val prefs       = remember { context.getSharedPreferences("crowdpath_settings", android.content.Context.MODE_PRIVATE) }
    val useWifiCane = remember { prefs.getBoolean("cane_use_wifi", true) }
    val caneClient  = remember {
        if (useWifiCane) null else CaneClient(context).also { it.startScan() }
    }
    val caneUdpClient = remember {
        if (useWifiCane) CaneUdpClient(context).also { it.startScan() } else null
    }
    // NavigationEngine accepts CaneClient? — for WiFi mode we pass null to engine
    // and drive caneUdpClient directly through the same callbacks below
    val engine      = remember { NavigationEngine(context, caneClient) }
    val db          = remember { AppDatabase.getInstance(context) }
    val repo        = remember { MapRepository(db) }

    var destination       by remember { mutableStateOf("") }
    var startLocation     by remember { mutableStateOf("") }   // user's current node label
    var isNavigating      by remember { mutableStateOf(false) }
    var currentInstruction by remember { mutableStateOf("") }
    var totalStepsWalked  by remember { mutableIntStateOf(0) }
    var totalDistWalked   by remember { mutableFloatStateOf(0f) }
    var prevEdgeDist      by remember { mutableFloatStateOf(0f) }
    var bleConnected      by remember { mutableStateOf(false) }
    var availableNodes    by remember { mutableStateOf(listOf<String>()) }
    var allMaps           by remember { mutableStateOf(listOf<CachedMapEntity>()) }
    var selectedMap       by remember { mutableStateOf<CachedMapEntity?>(null) }
    var hasMap            by remember { mutableStateOf(false) }
    var isListening       by remember { mutableStateOf(false) }
    var voiceHint         by remember { mutableStateOf("") }

    // Load ALL maps; user picks which one to navigate
    LaunchedEffect(Unit) {
        val maps = repo.getCachedMaps().firstOrNull() ?: emptyList()
        allMaps = maps
        val first = maps.firstOrNull()
        if (first != null) {
            selectedMap    = first
            availableNodes = first.mapData.nodes.map { it.label }
            // Default start = first mapped node (the mapping start point)
            startLocation  = availableNodes.firstOrNull() ?: ""
            hasMap = true
        }
    }

    // Wire engine callbacks
    DisposableEffect(Unit) {
        val stepLenM = context
            .getSharedPreferences("crowdpath_settings", Context.MODE_PRIVATE)
            .getFloat("step_length", 0.7f)

        engine.onProgressUpdate = { _, distOnEdge, _ ->
            // Add the per-edge distance delta to the running total.
            // We store the previous value and only add the difference
            // so crossing an edge boundary doesn't reset the display.
            val delta = (distOnEdge - prevEdgeDist).coerceAtLeast(0f)
            prevEdgeDist     = distOnEdge
            totalDistWalked += delta
            totalStepsWalked = (totalDistWalked / stepLenM).toInt()
        }
        // NOTE: Navigation vibration (left/right/arrived patterns) is fired by
        // HapticFeedbackManager inside the engine. Do NOT vibrate here too —
        // two concurrent vibrator.vibrate() calls cancel each other mid-pattern.
        engine.onInstructionUpdate = { instruction ->
            currentInstruction = instruction
        }
        engine.onArrival = {
            isNavigating       = false
            currentInstruction = "You have arrived!"
            totalStepsWalked   = 0
            totalDistWalked    = 0f
            prevEdgeDist       = 0f
        }
        onDispose { engine.shutdown() }
    }

    AnimatedContent(
        targetState  = isNavigating,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label        = "nav_state"
    ) { navigating ->
        if (!navigating) {
            PreNavigationView(
                context        = context,
                hasMap         = hasMap,
                selectedMap    = selectedMap,
                allMaps        = allMaps,
                availableNodes = availableNodes,
                startLocation  = startLocation,
                destination    = destination,
                isListening    = isListening,
                voiceHint      = voiceHint,
                bleConnected   = bleConnected,
                onStartLocationChange = { startLocation = it },
                onDestinationChange = { destination = it },
                onMapSelected = { map ->
                    selectedMap    = map
                    availableNodes = map.mapData.nodes.map { it.label }
                    startLocation  = availableNodes.firstOrNull() ?: ""
                    hasMap      = true
                    destination = ""  // reset destination when map changes
                },
                onVoiceStart = {
                    vibratePhone(context, HapticType.TAP)
                    isListening = true
                    voiceHint   = "Listening…"
                    startVoiceInput(context,
                        onResult = { spoken ->
                            destination = spoken
                            isListening = false
                            voiceHint   = "Destination: $spoken"
                            vibratePhone(context, HapticType.CONFIRM)
                        },
                        onError = {
                            isListening = false
                            voiceHint   = "Didn't catch that. Try again."
                            vibratePhone(context, HapticType.ERROR)
                        }
                    )
                },
                onStart = {
                    vibratePhone(context, HapticType.CONFIRM)
                    coroutineScope.launch {
                        val map = selectedMap ?: return@launch
                        engine.loadMap(map.mapData)

                        val start = map.mapData.nodes.find {
                            it.label.equals(startLocation, ignoreCase = true)
                        } ?: map.mapData.nodes.firstOrNull()

                        val end = map.mapData.nodes.find {
                            it.label.contains(destination, ignoreCase = true)
                        }

                        if (start == null || end == null) {
                            voiceHint = "Could not find that destination. Try again."
                            vibratePhone(context, HapticType.ERROR)
                            return@launch
                        }
                        if (start.id == end.id) {
                            voiceHint = "You are already there!"
                            vibratePhone(context, HapticType.CONFIRM)
                            return@launch
                        }

                        val summary = engine.startNavigation(start.id, end.id)
                        if (summary != null) {
                            currentInstruction = "Starting navigation…"
                            isNavigating = true
                        } else {
                            voiceHint = "No route found. Check your start and destination."
                            vibratePhone(context, HapticType.ERROR)
                        }
                    }
                }
            )
        } else {
            LiveNavigationView(
                instruction    = currentInstruction,
                stepsWalked    = totalStepsWalked,
                distanceWalked = totalDistWalked,
                onStop = {
                    engine.stopNavigation()
                    isNavigating       = false
                    currentInstruction = ""
                    totalStepsWalked   = 0
                    totalDistWalked    = 0f
                    prevEdgeDist       = 0f
                    startLocation  = ""
                    availableNodes = selectedMap?.mapData?.nodes?.map { it.label } ?: emptyList()
                    startLocation  = availableNodes.firstOrNull() ?: ""
                    vibratePhone(context, HapticType.TAP)
                }
            )
        }
    }
}

// ── PRE-NAVIGATION: simple, large-target, voice-first ─────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PreNavigationView(
    context: Context,
    hasMap: Boolean,
    selectedMap: CachedMapEntity?,
    allMaps: List<CachedMapEntity>,
    availableNodes: List<String>,
    startLocation: String,
    destination: String,
    isListening: Boolean,
    voiceHint: String,
    bleConnected: Boolean,
    onStartLocationChange: (String) -> Unit,
    onDestinationChange: (String) -> Unit,
    onMapSelected: (CachedMapEntity) -> Unit,
    onVoiceStart: () -> Unit,
    onStart: () -> Unit
) {
    // Mic pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.18f,
        animationSpec = infiniteRepeatable(tween(700, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "mic_scale"
    )
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(NavyBg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Gradient header ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D2552), NavyBg)))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column {
                Text(
                    "Navigate",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                if (!hasMap) {
                    Text(
                        "⚠ No map yet — ask a volunteer to build one first",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(SuccessGreen))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Map ready — ${selectedMap?.name ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessGreen
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Map selector — shown only when multiple maps exist ───
        if (allMaps.size > 1) {
            Text(
                "Select map:",
                style    = MaterialTheme.typography.labelMedium,
                color    = SlateText,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allMaps) { map ->
                    val isActive = selectedMap?.id == map.id
                    Surface(
                        onClick  = { onMapSelected(map) },
                        shape    = RoundedCornerShape(20.dp),
                        color    = if (isActive) ElectricBlue else NavyCard,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(
                            modifier         = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                map.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isActive) Color.White else SlateText
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(20.dp))
        }

        // ── GIANT mic button — the main action ───────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier
                .size(if (isListening) 160.dp else 140.dp)
                .scale(if (isListening) micScale else 1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (isListening) listOf(CyanAccent, ElectricBlue)
                        else             listOf(ElectricBlue, Color(0xFF0D2552))
                    )
                )
                .semantics { contentDescription = "Say your destination" }
                .pointerInput(Unit) {
                    detectTapGestures { onVoiceStart() }
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isListening) Icons.Default.HearingDisabled else Icons.Default.Mic,
                    contentDescription = null,
                    tint     = Color.White,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isListening) "Listening…" else "Tap & Speak",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Voice hint / result feedback
        if (voiceHint.isNotBlank()) {
            Text(
                voiceHint,
                style     = MaterialTheme.typography.bodyMedium,
                color     = if (voiceHint.startsWith("Destination")) SuccessGreen else SlateText,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 32.dp)
            )
        } else {
            Text(
                "Or type below",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Text input (secondary, for sighted helpers) ─────────
        OutlinedTextField(
            value         = destination,
            onValueChange = onDestinationChange,
            label         = { Text("Destination", color = SlateText) },
            placeholder   = { Text("e.g., Room 205, Exit, Stairs", color = SlateText) },
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape         = RoundedCornerShape(14.dp),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboardController?.hide() }
            ),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = ElectricBlue,
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                cursorColor          = ElectricBlue
            )
        )

        // ── "Where are you now?" chip row ───────────────────────
        if (availableNodes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "📍 Where are you now?",
                style    = MaterialTheme.typography.labelMedium,
                color    = SlateText,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableNodes) { label ->
                    val isSelected = startLocation == label
                    Surface(
                        onClick  = { onStartLocationChange(label) },
                        shape    = RoundedCornerShape(20.dp),
                        color    = if (isSelected) SuccessGreen else NavyCard,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(
                            modifier         = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) Color.White else SlateText
                            )
                        }
                    }
                }
            }
        }

        // ── Destination quick-pick chips ─────────────────────────
        val destinationNodes = availableNodes.filter { it != startLocation }
        if (destinationNodes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "🏁 Go to:",
                style    = MaterialTheme.typography.labelMedium,
                color    = SlateText,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding      = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(destinationNodes) { label ->
                    val isSelected = destination == label
                    Surface(
                        onClick  = {
                            onDestinationChange(label)
                            keyboardController?.hide()
                        },
                        shape    = RoundedCornerShape(20.dp),
                        color    = if (isSelected) ElectricBlue else NavyCard,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(
                            modifier         = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style  = MaterialTheme.typography.labelMedium,
                                color  = if (isSelected) Color.White else SlateText
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── BLE status pill ──────────────────────────────────────
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (bleConnected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint     = if (bleConnected) SuccessGreen else SlateText,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (bleConnected) "Smart cane connected" else "Smart cane not connected",
                style = MaterialTheme.typography.labelSmall,
                color = if (bleConnected) SuccessGreen else SlateText
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── START button — LARGE, full width ────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (destination.isNotBlank() && hasMap)
                        Brush.horizontalGradient(listOf(ElectricBlue, CyanAccent))
                    else
                        Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF1E293B)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick  = {
                    keyboardController?.hide()
                    onStart()
                },
                modifier = Modifier.fillMaxSize(),
                shape    = RoundedCornerShape(20.dp),
                enabled  = destination.isNotBlank() && hasMap,
                colors   = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Start Navigation",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── LIVE NAVIGATION: audio-first, massive instruction card ────────────────

@Composable
private fun LiveNavigationView(
    instruction: String,
    stepsWalked: Int,
    distanceWalked: Float,
    onStop: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")

    // Direction icon + color derived from instruction text
    val lc = instruction.lowercase()
    val (dirIcon, dirColor) = when {
        lc.contains("left")    -> Pair(Icons.Default.TurnLeft, CyanAccent)
        lc.contains("right")   -> Pair(Icons.Default.TurnRight, Color(0xFF7C3AED))
        lc.contains("stairs")  -> Pair(Icons.Default.Stairs, AmberWarning)
        lc.contains("arrived") -> Pair(Icons.Default.CheckCircle, SuccessGreen)
        else                   -> Pair(Icons.Default.ArrowUpward, ElectricBlue)
    }

    // Pulsing background behind direction icon
    val bgAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.08f,
        targetValue   = 0.22f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "bg_alpha"
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(NavyBg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.08f))

        // ── Giant direction icon with pulsing glow ───────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(dirColor.copy(alpha = bgAlpha))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(dirColor.copy(alpha = bgAlpha * 1.5f))
            ) {
                Icon(
                    dirIcon,
                    contentDescription = null,
                    modifier = Modifier.size(90.dp),
                    tint     = dirColor
                )
            }
        }

        Spacer(Modifier.weight(0.06f))

        // ── Instruction text — very large, centred ───────────────
        Text(
            text       = instruction,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
            textAlign  = TextAlign.Center,
            lineHeight = 36.sp,
            modifier   = Modifier.padding(horizontal = 28.dp)
        )

        Spacer(Modifier.weight(0.05f))

        // ── Vibration legend card ─────────────────────────────────────
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(NavyCard)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Phone vibration guide:",
                style     = MaterialTheme.typography.labelMedium,
                color     = SlateText
            )
            HapticLegendRow("●",         "1 buzz  →  Turn LEFT",    CyanAccent)
            HapticLegendRow("● ●",       "2 buzzes  →  Turn RIGHT", Color(0xFF7C3AED))
            HapticLegendRow("● ● ●",     "3 buzzes  →  Arrived / Stop", SuccessGreen)
            HapticLegendRow("(silence)",  "No buzz  →  Keep walking straight", SlateText)
        }

        Spacer(Modifier.weight(0.08f))

        // ── Progress stats ───────────────────────────────────────
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NavyCard)
                .padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LiveStat("$stepsWalked", "Steps", ElectricBlue)
            Box(
                Modifier
                    .width(1.dp)
                    .height(48.dp)
                    .background(Color(0xFF334155))
            )
            LiveStat("%.0f m".format(distanceWalked), "Distance", CyanAccent)
        }

        Spacer(Modifier.weight(0.1f))

        // ── STOP button — also large but red, bottom of screen ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ErrorRed),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick  = onStop,
                modifier = Modifier.fillMaxSize(),
                shape    = RoundedCornerShape(20.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Stop Navigation",
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LiveStat(value: String, label: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(label, style = MaterialTheme.typography.labelMedium, color = SlateText)
    }
}

@Composable
private fun HapticLegendRow(dots: String, description: String, accent: Color) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            dots,
            fontWeight = FontWeight.Bold,
            fontSize   = 11.sp,
            color      = accent,
            modifier   = Modifier.width(52.dp)
        )
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = SlateText
        )
    }
}


// ── Voice input via Android SpeechRecognizer ──────────────────────────────

private fun startVoiceInput(
    context: Context,
    onResult: (String) -> Unit,
    onError: () -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError()
        return
    }

    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent     = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spoken  = matches?.firstOrNull()?.trim() ?: ""
            recognizer.destroy()
            if (spoken.isNotBlank()) onResult(spoken) else onError()
        }
        override fun onError(error: Int) { recognizer.destroy(); onError() }
        override fun onReadyForSpeech(params: Bundle)   {}
        override fun onBeginningOfSpeech()              {}
        override fun onRmsChanged(rmsdB: Float)         {}
        override fun onBufferReceived(buffer: ByteArray){}
        override fun onEndOfSpeech()                    {}
        override fun onPartialResults(partialResults: Bundle) {}
        override fun onEvent(eventType: Int, params: Bundle)  {}
    })

    recognizer.startListening(intent)
}
