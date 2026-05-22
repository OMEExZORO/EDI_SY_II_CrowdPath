package com.crowdpath.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crowdpath.app.navigation.TTSGuide
import com.crowdpath.app.ui.mapping.MappingScreen
import com.crowdpath.app.ui.maps.MapManagementScreen
import com.crowdpath.app.ui.navigation.NavigationScreen
import com.crowdpath.app.ui.onboarding.OnboardingScreen
import com.crowdpath.app.ui.settings.SettingsScreen
import com.crowdpath.app.ui.theme.CrowdPathTheme
import com.crowdpath.app.ui.theme.CyanAccent
import com.crowdpath.app.ui.theme.ElectricBlue
import com.crowdpath.app.ui.theme.NavyBg
import com.crowdpath.app.ui.theme.NavySurface

private const val PREFS_NAME   = "crowdpath_prefs"
private const val KEY_ONBOARD  = "onboarding_complete"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PermissionsHelper.requestAll(
            activity = this,
            onGranted = {},
            onDenied  = { denied -> android.util.Log.w("MainActivity", "Denied: $denied") }
        )

        setContent {
            CrowdPathTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val tts     = remember { com.crowdpath.app.navigation.TTSGuide(context) }
    var onboardingDone by remember {
        mutableStateOf(prefs.getBoolean(KEY_ONBOARD, false))
    }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    if (!onboardingDone) {
        OnboardingScreen(
            onFinished = {
                prefs.edit().putBoolean(KEY_ONBOARD, true).apply()
                onboardingDone = true
            }
        )
    } else {
        MainScreen(tts = tts)
    }
}

// ── Tab definitions ────────────────────────────────────────────────────────

private data class AppTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val announcement: String  // spoken when tab is selected
)

private val appTabs = listOf(
    AppTab("navigate", "Navigate", Icons.Default.Explore,
        "Navigate. Tap the big blue button and say your destination."),
    AppTab("mapping",  "Map Building", Icons.Default.Map,
        "Map Building. Walk through a building and mark landmarks."),
    AppTab("maps",     "My Maps", Icons.Default.Storage,
        "My Maps. Your saved indoor maps."),
    AppTab("settings", "Settings", Icons.Default.Settings,
        "Settings."),
)

// ── Main scaffold ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(tts: TTSGuide? = null) {
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = NavyBg,
        topBar = {
            // Gradient header bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0D2552), Color(0xFF0A1628))
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Logo dot
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(ElectricBlue, CyanAccent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Explore,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "CrowdPath",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    appTabs.forEachIndexed { index, tab ->
                        AnimatedNavItem(
                            tab       = tab,
                            selected  = selectedTab == index,
                            onClick   = {
                                selectedTab = index
                                tts?.announce(tab.announcement)
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "navigate",
            modifier         = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("navigate") { NavigationScreen() }
            composable("mapping")  { MappingScreen() }
            composable("maps")     { MapManagementScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

// ── Animated bottom nav item ───────────────────────────────────────────────

@Composable
private fun AnimatedNavItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "nav_scale"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) ElectricBlue else Color(0xFF64748B),
        label = "nav_color"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) ElectricBlue else Color(0xFF64748B),
        label = "nav_label_color"
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "nav_indicator"
    )

    Surface(
        onClick   = onClick,
        color     = Color.Transparent,
        shape     = RoundedCornerShape(16.dp),
        modifier  = Modifier
            .width(82.dp)
            .height(64.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(scale)
        ) {
            // Pill indicator behind icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(ElectricBlue.copy(alpha = 0.15f * indicatorAlpha))
                )
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint     = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                tab.label,
                fontSize   = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color      = labelColor,
                maxLines   = 1
            )
        }
    }
}
