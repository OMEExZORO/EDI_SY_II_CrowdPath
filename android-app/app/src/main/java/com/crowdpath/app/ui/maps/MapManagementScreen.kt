package com.crowdpath.app.ui.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crowdpath.app.data.database.AppDatabase
import com.crowdpath.app.data.database.CachedMapEntity
import com.crowdpath.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MapManagementScreen() {
    val context        = LocalContext.current
    val db             = remember { AppDatabase.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    val maps by db.mapDao().getAllMaps().collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBg)
    ) {
        // ── Header ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(colors = listOf(Color(0xFF0D2552), NavyBg))
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    "My Maps",
                    style      = MaterialTheme.typography.headlineSmall,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (maps.isEmpty()) "No maps saved yet" else "${maps.size} map${if (maps.size > 1) "s" else ""} saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
        }

        if (maps.isEmpty()) {
            // ── Empty state ────────────────────────────────────────
            Box(
                modifier         = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(ElectricBlue.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint     = ElectricBlue.copy(alpha = 0.4f)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "No maps yet",
                        style      = MaterialTheme.typography.titleLarge,
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Go to Map Building to create your first indoor map",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = SlateText
                    )
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding      = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(maps, key = { it.id }) { map ->
                    MapCard(
                        map      = map,
                        onDelete = {
                            coroutineScope.launch { db.mapDao().deleteMap(map.id) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MapCard(
    map: CachedMapEntity,
    onDelete: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Map icon avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.3f), CyanAccent.copy(alpha = 0.1f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Map,
                contentDescription = null,
                tint     = ElectricBlue,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                map.name,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White,
                fontSize   = 15.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${map.mapData.nodes.size} checkpoint${if (map.mapData.nodes.size != 1) "s" else ""} · ${map.mapData.edges.size} connection${if (map.mapData.edges.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = CyanAccent
            )
            if (map.mapData.nodes.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    map.mapData.nodes.joinToString(", ") { it.label },
                    style    = MaterialTheme.typography.bodySmall,
                    color    = SlateText,
                    maxLines = 2
                )
            }
        }

        IconButton(
            onClick  = { showConfirm = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint     = ErrorRed.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor   = NavyCard,
            title            = { Text("Delete Map", color = Color.White, fontWeight = FontWeight.Bold) },
            text             = {
                Text(
                    "Delete \"${map.name}\"? This cannot be undone.",
                    color = SlateText
                )
            },
            confirmButton    = {
                Button(
                    onClick = { onDelete(); showConfirm = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton    = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = SlateText)
                }
            }
        )
    }
}
