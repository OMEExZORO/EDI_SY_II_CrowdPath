package com.crowdpath.app.ui.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class OnboardingSlide(
    val icon: ImageVector,
    val iconTint: Color,
    val backgroundAccent: Color,
    val title: String,
    val subtitle: String,
    val bodyLines: List<String>
)

val onboardingSlides = listOf(
    OnboardingSlide(
        icon             = Icons.Default.Explore,
        iconTint         = Color(0xFF3D8EFF),
        backgroundAccent = Color(0xFF3D8EFF),
        title            = "Welcome to CrowdPath",
        subtitle         = "Indoor navigation for the visually impaired",
        bodyLines        = listOf(
            "CrowdPath gives turn-by-turn audio directions inside buildings — no internet, no GPS needed.",
            "Everything speaks to you. Your phone vibrates at every turn."
        )
    ),
    OnboardingSlide(
        icon             = Icons.Default.RecordVoiceOver,
        iconTint         = Color(0xFF00D4FF),
        backgroundAccent = Color(0xFF00D4FF),
        title            = "How Navigation Works",
        subtitle         = "Audio-first. Zero screen reading required.",
        bodyLines        = listOf(
            "1. Tap the big mic button and say your destination.",
            "2. Tap Start Navigation.",
            "3. Walk. Your phone speaks every instruction aloud.",
            "4. The phone vibrates left or right at every turn.",
            "That's it — you never need to look at the screen."
        )
    ),
    OnboardingSlide(
        icon             = Icons.Default.Map,
        iconTint         = Color(0xFF7C3AED),
        backgroundAccent = Color(0xFF7C3AED),
        title            = "Maps Are Built By Volunteers",
        subtitle         = "You only need to navigate — not map",
        bodyLines        = listOf(
            "A sighted volunteer uses the Map Building tab once to record a building.",
            "After that, you can navigate that building anytime.",
            "Ask a family member, friend, or building staff to build the map for you."
        )
    ),
    OnboardingSlide(
        icon             = Icons.Default.Bluetooth,
        iconTint         = Color(0xFF10B981),
        backgroundAccent = Color(0xFF10B981),
        title            = "Smart Cane (Optional)",
        subtitle         = "Extra haptic guidance in your hand",
        bodyLines        = listOf(
            "If you have a CrowdPath smart cane, it vibrates with direction cues.",
            "Short pulse = go straight. Double pulse = turn ahead. Long pulse = arrived.",
            "The app works perfectly without the cane too."
        )
    ),
    OnboardingSlide(
        icon             = Icons.Default.CheckCircle,
        iconTint         = Color(0xFF3D8EFF),
        backgroundAccent = Color(0xFF3D8EFF),
        title            = "Ready to Navigate",
        subtitle         = "One tap. Speak. Walk.",
        bodyLines        = listOf(
            "Open the Navigate tab.",
            "Tap the big blue circle and say where you want to go.",
            "Follow the voice and vibrations.",
            "You're in control."
        )
    )
)
