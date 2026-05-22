package com.crowdpath.app.ui.mapping

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crowdpath.app.data.database.AppDatabase
import com.crowdpath.app.data.database.CachedMapEntity
import com.crowdpath.app.data.models.*
import com.crowdpath.app.mapping.GraphBuilder
import com.crowdpath.app.mapping.WiFiScanner
import com.crowdpath.app.navigation.PDRTracker
import com.crowdpath.app.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingScreen() {
    val context       = LocalContext.current
    val graphBuilder  = remember { GraphBuilder() }
    val wifiScanner   = remember { WiFiScanner(context) }
    val pdrTracker    = remember { PDRTracker(context) }  // step counting for fallback poses
    val coroutineScope = rememberCoroutineScope()
    val db            = remember { AppDatabase.getInstance(context) }

    var isMapping         by remember { mutableStateOf(false) }
    var nodeLabel         by remember { mutableStateOf("") }
    var selectedNodeType  by remember { mutableStateOf(NodeType.ROOM) }
    var selectedTurnDir   by remember { mutableStateOf<TurnDirection?>(null) }
    var nodeList          by remember { mutableStateOf(listOf<Node>()) }
    var showNodeDialog    by remember { mutableStateOf(false) }
    var totalDistance     by remember { mutableFloatStateOf(0f) }
    var showSaveDialog    by remember { mutableStateOf(false) }
    var buildingName      by remember { mutableStateOf("") }
    var statusMessage     by remember { mutableStateOf("") }
    var currentFloor      by remember { mutableIntStateOf(0) }
    var currentArPose     by remember { mutableStateOf<Pose3D?>(null) }
    var isArTracking      by remember { mutableStateOf(false) }
    // Accumulated pose for step-based fallback.
    // These are updated from real PDR step counts, not random numbers.
    var poseX             by remember { mutableFloatStateOf(0f) }
    var poseY             by remember { mutableFloatStateOf(0f) }
    var stepsSinceLastCheckpoint by remember { mutableIntStateOf(0) }

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val recAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "rec_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBg)
    ) {
        // ── Page header ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0D2552), NavyBg)
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isMapping) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ErrorRed.copy(alpha = recAlpha))
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text      = if (isMapping) "Mapping Active" else "Map a Building",
                        style     = MaterialTheme.typography.headlineSmall,
                        color     = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (isMapping) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Walk to landmarks and tap Mark Checkpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Walk through a building to create an indoor map",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }
        }

        // ── AR Camera / PDR card ───────────────────────────────────
        AnimatedVisibility(
            visible = isMapping,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                ArCameraView(
                    isActive         = true,
                    onPoseUpdated    = { pose -> currentArPose = pose },
                    onTrackingChanged = { tracking -> isArTracking = tracking },
                    modifier         = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width  = 1.5.dp,
                            color  = if (isArTracking) SuccessGreen.copy(alpha = 0.5f)
                                     else ElectricBlue.copy(alpha = 0.3f),
                            shape  = RoundedCornerShape(16.dp)
                        )
                )
                Spacer(Modifier.height(8.dp))

                // Tracking status badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isArTracking) SuccessGreen else CyanAccent)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = if (isArTracking) "AR Tracking Active" else "Step Tracking Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isArTracking) SuccessGreen else CyanAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    if (isArTracking && currentArPose != null) {
                        Text(
                            "(%.1f, %.1f, %.1f)".format(
                                currentArPose!!.x, currentArPose!!.y, currentArPose!!.z
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateText
                        )
                    } else if (!isArTracking && stepsSinceLastCheckpoint > 0) {
                        Text(
                            "$stepsSinceLastCheckpoint steps (%.1fm)".format(
                                stepsSinceLastCheckpoint * 0.7f
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateText
                        )
                    }
                }
            }
        }

        // ── Stat pills (shown during mapping) ──────────────────────
        AnimatedVisibility(visible = isMapping) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatPill("Checkpoints", "${graphBuilder.nodeCount}",  ElectricBlue, Modifier.weight(1f))
                StatPill("Connections", "${graphBuilder.edgeCount}",  CyanAccent,  Modifier.weight(1f))
                StatPill("Distance",    "%.1fm".format(totalDistance), Color(0xFF7C3AED), Modifier.weight(1f))
            }
        }

        // ── Floor selector (during mapping) ───────────────────────
        AnimatedVisibility(visible = isMapping) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NavyCard)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Floor", style = MaterialTheme.typography.labelLarge, color = SlateText)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = { currentFloor-- },
                        modifier = Modifier.size(32.dp),
                        colors   = IconButtonDefaults.filledIconButtonColors(containerColor = NavyBg)
                    ) { Text("−", color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "$currentFloor",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 20.sp,
                        color      = Color.White
                    )
                    Spacer(Modifier.width(16.dp))
                    FilledIconButton(
                        onClick = { currentFloor++ },
                        modifier = Modifier.size(32.dp),
                        colors   = IconButtonDefaults.filledIconButtonColors(containerColor = NavyBg)
                    ) { Text("+", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Control buttons ────────────────────────────────────────
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (isMapping) {
                        if (graphBuilder.nodeCount < 2) {
                            statusMessage = "⚠️ Mark at least 1 checkpoint after the start before finishing."
                            return@Button
                        }
                        isMapping = false
                        pdrTracker.stop()
                        showSaveDialog = true
                    } else {
                        graphBuilder.clear()
                        nodeList      = emptyList()
                        totalDistance = 0f
                        poseX         = 0f
                        poseY         = 0f
                        stepsSinceLastCheckpoint = 0
                        currentArPose = null
                        isArTracking  = false
                        statusMessage = ""
                        currentFloor  = 0
                        nodeLabel     = "Start"   // pre-fill as hint, volunteer can edit
                        selectedNodeType = NodeType.ROOM
                        selectedTurnDir  = null
                        isMapping     = true
                        // Open the dialog immediately so volunteer names the starting point
                        showNodeDialog = true
                        // Start step counting for fallback pose estimation
                        pdrTracker.onStep = { steps, _ ->
                            stepsSinceLastCheckpoint = steps
                        }
                        pdrTracker.start()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMapping) ErrorRed else ElectricBlue
                )
            ) {
                Icon(
                    if (isMapping) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isMapping) "Finish Mapping" else "Start Mapping",
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isMapping) {
                Button(
                    onClick = { showNodeDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape  = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyCard)
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mark Checkpoint", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Status message ─────────────────────────────────────────
        if (statusMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (statusMessage.startsWith("✅"))
                            SuccessGreen.copy(alpha = 0.12f)
                        else ErrorRed.copy(alpha = 0.12f)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusMessage.startsWith("✅")) SuccessGreen else AmberWarning
                )
            }
        }

        // ── Node list ──────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Marked Checkpoints",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White
            )
            if (nodeList.isNotEmpty()) {
                Text(
                    "${nodeList.size} checkpoint${if (nodeList.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlateText
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (nodeList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AddLocation,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint     = SlateText.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isMapping) "Walk to a landmark and tap Mark Checkpoint"
                        else "Tap Start Mapping to begin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText
                    )
                }
            }
        }

        LazyColumn(
            modifier            = Modifier.weight(1f),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(nodeList) { node -> NodeItem(node) }
        }
    }

    // ── Mark Node Dialog ───────────────────────────────────────────
    if (showNodeDialog) {
        AlertDialog(
            onDismissRequest = { showNodeDialog = false },
            containerColor   = NavyCard,
            title = {
                Text(
                    "Mark Location",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // ── Compact tracking status pill ──────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isArTracking && currentArPose != null)
                                    SuccessGreen.copy(alpha = 0.1f) else CyanAccent.copy(alpha = 0.08f)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(7.dp).clip(CircleShape)
                                .background(if (isArTracking && currentArPose != null) SuccessGreen else CyanAccent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isArTracking && currentArPose != null)
                                "ARCore active — high accuracy"
                            else "Step-based positioning",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isArTracking && currentArPose != null) SuccessGreen else CyanAccent
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── DIRECTION PICKER — always shown, prominent at top ─────
                    val isFirstCheckpoint = graphBuilder.nodeCount == 0
                    if (isFirstCheckpoint) {
                        // First checkpoint: no incoming direction — show grey banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "📍 Starting Point — no turn direction needed",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateText,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            "Which way did you walk to get here?",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))

                        // Row 1: Sharp Left · Left · Straight · Right · Sharp Right
                        val turnOptions = listOf(
                            TurnDirection.SHARP_LEFT  to "↰ Sharp\nLeft",
                            TurnDirection.LEFT        to "← Left",
                            TurnDirection.STRAIGHT    to "↑ Fwd",
                            TurnDirection.RIGHT       to "→ Right",
                            TurnDirection.SHARP_RIGHT to "↱ Sharp\nRight"
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            turnOptions.forEach { (dir, label) ->
                                val isSelected = selectedTurnDir == dir
                                Surface(
                                    onClick = { selectedTurnDir = if (isSelected) null else dir },
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                    color    = if (isSelected) ElectricBlue else NavyBg,
                                    tonalElevation = if (isSelected) 4.dp else 0.dp
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                                    ) {
                                        Text(
                                            text       = label,
                                            fontSize   = 11.sp,
                                            color      = if (isSelected) Color.White else SlateText,
                                            textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // Row 2: Slight Left · Slight Right
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                TurnDirection.SLIGHT_LEFT  to "↖ Slight Left",
                                TurnDirection.SLIGHT_RIGHT to "↗ Slight Right"
                            ).forEach { (dir, label) ->
                                val isSelected = selectedTurnDir == dir
                                Surface(
                                    onClick = { selectedTurnDir = if (isSelected) null else dir },
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                    color    = if (isSelected) ElectricBlue else NavyBg
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text       = label,
                                            fontSize   = 11.sp,
                                            color      = if (isSelected) Color.White else SlateText,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedTurnDir == null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap a direction above (or skip — ARCore will estimate)",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── Location label ────────────────────────────────────────
                    OutlinedTextField(
                        value         = nodeLabel,
                        onValueChange = { nodeLabel = it },
                        label         = { Text("Location label", color = SlateText) },
                        placeholder   = { Text("e.g., Room 205, Stairs A", color = SlateText) },
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = ElectricBlue,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    // ── Location type chips ───────────────────────────────────
                    Text("Location type:", style = MaterialTheme.typography.labelMedium, color = SlateText)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NodeType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedNodeType == type,
                                onClick  = { selectedNodeType = type },
                                label    = { Text(type.name, style = MaterialTheme.typography.labelSmall) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue,
                                    selectedLabelColor     = Color.White
                                )
                            )
                        }
                    }
                }
            },

            confirmButton = {
                Button(
                    onClick = {
                        // Determine pose: prefer ARCore real coordinates.
                        // Fallback: use actual steps walked since last checkpoint
                        // (0.7m per step) so edge lengths are realistic, not random.
                        val pose = currentArPose ?: run {
                            val distSinceLastNode = stepsSinceLastCheckpoint * pdrTracker.stepLengthM
                            // Advance position in the current heading direction.
                            // Use simple forward-X accumulation; heading from rotation sensor.
                            val headingRad = Math.toRadians(pdrTracker.currentHeading.toDouble())
                            poseX += (distSinceLastNode * Math.sin(headingRad)).toFloat()
                            poseY += (distSinceLastNode * Math.cos(headingRad)).toFloat()
                            Pose3D(poseX, poseY, 0f)
                        }
                        // Reset step counter for the next segment
                        stepsSinceLastCheckpoint = 0
                        pdrTracker.resetSteps()

                        val wifiReadings = wifiScanner.takeSnapshot()
                        val node = Node(
                            id          = "n_${UUID.randomUUID().toString().take(6)}",
                            label       = nodeLabel.ifBlank { "Checkpoint ${graphBuilder.nodeCount + 1}" },
                            floor       = currentFloor,
                            pose        = pose,
                            fingerprints = Fingerprints(wifi = wifiReadings),
                            type        = selectedNodeType,
                            timestamp   = System.currentTimeMillis()
                        )
                        graphBuilder.addNode(node, selectedTurnDir)
                        nodeList      = nodeList + node
                        totalDistance = graphBuilder.getTotalDistance()
                        nodeLabel      = ""
                        selectedTurnDir = null
                        showNodeDialog = false
                        val src = if (currentArPose != null) "ARCore" else "Step-based"
                        statusMessage = "✅ ${node.label} saved ($src, ${wifiReadings.size} nearby signals)"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) { Text("Save Checkpoint", color = Color.White, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showNodeDialog = false }) {
                    Text("Cancel", color = SlateText)
                }
            }
        )
    }

    // ── Save Map Dialog ────────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor   = NavyCard,
            title = {
                Text("Save Map", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "${graphBuilder.nodeCount} checkpoints · ${graphBuilder.edgeCount} connections · %.1fm".format(totalDistance),
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                    val wifiTotal = nodeList.sumOf { it.fingerprints.wifi.size }
                    Text(
                        "$wifiTotal location signals captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value         = buildingName,
                        onValueChange = { buildingName = it },
                        label         = { Text("Building name", color = SlateText) },
                        placeholder   = { Text("e.g., Main Building Floor 1", color = SlateText) },
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = ElectricBlue,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (buildingName.isBlank()) {
                            statusMessage = "⚠️ Enter a building name."
                            return@Button
                        }
                        val mapData    = graphBuilder.build()
                        val buildingId = "bld_${UUID.randomUUID().toString().take(8)}"
                        coroutineScope.launch {
                            val entity = CachedMapEntity(
                                id         = buildingId,
                                name       = buildingName.trim(),
                                uploadedBy = "local_mapper",
                                uploadDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                                mapData    = mapData,
                                version    = 1,
                                isPublic   = true
                            )
                            db.mapDao().insertMap(entity)
                            statusMessage = "✅ Map \"${buildingName.trim()}\" saved! Go to Navigate tab."
                            buildingName  = ""
                        }
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) { Text("Save Map", color = Color.White, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = SlateText)
                }
            }
        )
    }
}

// ── Reusable Components ────────────────────────────────────────────────────

@Composable
private fun StatPill(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.1f))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent)
            Text(label, style = MaterialTheme.typography.labelSmall, color = SlateText)
        }
    }
}

@Composable
private fun NodeItem(node: Node) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NavyCard)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ElectricBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when (node.type) {
                    NodeType.ROOM         -> Icons.Default.MeetingRoom
                    NodeType.STAIRS       -> Icons.Default.Stairs
                    NodeType.ELEVATOR     -> Icons.Default.Elevator
                    NodeType.INTERSECTION -> Icons.Default.CallSplit
                    NodeType.DOOR         -> Icons.Default.DoorFront
                    NodeType.ENTRANCE     -> Icons.Default.Login
                },
                contentDescription = node.type.name,
                tint     = ElectricBlue,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(node.label, fontWeight = FontWeight.Medium, color = Color.White)
            Text(
                "Floor ${node.floor} · ${node.type.name.lowercase().replaceFirstChar { it.uppercase() }} · ${node.fingerprints.wifi.size} nearby signals",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText
            )
        }
        Text(
            "#${node.id.takeLast(4)}",
            style = MaterialTheme.typography.labelSmall,
            color = SlateText.copy(alpha = 0.5f)
        )
    }
}
