package com.crowdpath.emulator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Composable that visually animates the current vibration pattern.
 *
 * Shows pattern number, icon, description, and a pulsing circle
 * whose colour and speed reflect the pattern type.
 */
@Composable
fun VibrationVisualizer(activePattern: Int) {
    val patternInfo = remember(activePattern) {
        when (activePattern) {
            1 -> PatternInfo("⬅️", "Turn Left", Color(0xFF42A5F5), 800)
            2 -> PatternInfo("➡️", "Turn Right", Color(0xFF66BB6A), 800)
            3 -> PatternInfo("🔔", "Near Turn", Color(0xFFFFA726), 500)
            4 -> PatternInfo("🪜", "Stairs Ahead", Color(0xFFAB47BC), 400)
            5 -> PatternInfo("🎉", "Arrived!", Color(0xFF26C6DA), 300)
            6 -> PatternInfo("🛑", "STOP — Obstacle", Color(0xFFEF5350), 200)
            else -> PatternInfo("⏸️", "Idle", Color.Gray, 0)
        }
    }

    // Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "vibe_pulse")
    val pulseScale by if (patternInfo.pulseMs > 0) {
        infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(patternInfo.pulseMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val animatedColor by animateColorAsState(
        targetValue = patternInfo.color,
        animationSpec = tween(300),
        label = "color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Pulsing circle
        Box(
            modifier = Modifier
                .size(100.dp)
                .scale(pulseScale)
                .background(animatedColor.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(animatedColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    patternInfo.icon,
                    fontSize = 28.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (activePattern > 0) "Pattern $activePattern" else "No Vibration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = patternInfo.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class PatternInfo(
    val icon: String,
    val label: String,
    val color: Color,
    val pulseMs: Int  // 0 = no animation
)
