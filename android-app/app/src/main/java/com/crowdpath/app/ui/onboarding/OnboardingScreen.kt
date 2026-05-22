package com.crowdpath.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingSlides.size })
    val scope      = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == onboardingSlides.lastIndex
    val slide      = onboardingSlides[pagerState.currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E))
    ) {
        // Ambient glow background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            slide.backgroundAccent.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button top-right
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                AnimatedVisibility(visible = !isLastPage) {
                    TextButton(onClick = onFinished) {
                        Text("Skip", color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Pager content
            HorizontalPager(
                state    = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                OnboardingSlidePage(slide = onboardingSlides[page])
            }

            // Progress dots
            Row(
                modifier              = Modifier.padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                repeat(onboardingSlides.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotColor   = if (isSelected) slide.backgroundAccent else Color(0xFF334155)
                    val dotWidth by animateDpAsState(
                        targetValue    = if (isSelected) 24.dp else 8.dp,
                        animationSpec  = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label          = "dot_width"
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }

            // CTA button
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinished()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(58.dp),
                shape  = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = slide.backgroundAccent)
            ) {
                Text(
                    text       = if (isLastPage) "Start Exploring" else "Next",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OnboardingSlidePage(slide: OnboardingSlide) {
    val infiniteTransition = rememberInfiniteTransition(label = "icon_pulse")
    val iconScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.08f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label         = "icon_scale"
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with glow rings
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier
                .size(140.dp)
                .scale(iconScale)
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(slide.backgroundAccent.copy(alpha = 0.12f))
            )
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(slide.backgroundAccent.copy(alpha = 0.2f))
            )
            Icon(
                imageVector    = slide.icon,
                contentDescription = slide.title,
                modifier       = Modifier.size(52.dp),
                tint           = slide.iconTint
            )
        }

        Spacer(Modifier.height(40.dp))

        Text(
            text       = slide.title,
            fontSize   = 26.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text       = slide.subtitle,
            fontSize   = 15.sp,
            color      = slide.iconTint,
            fontWeight = FontWeight.Medium,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        Column(
            horizontalAlignment = Alignment.Start,
            modifier            = Modifier.fillMaxWidth()
        ) {
            slide.bodyLines.forEach { line ->
                Row(
                    modifier          = Modifier.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp, end = 10.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(slide.backgroundAccent)
                    )
                    Text(
                        text       = line,
                        fontSize   = 14.sp,
                        color      = Color(0xFFCBD5E1),
                        lineHeight = 21.sp
                    )
                }
            }
        }
    }
}
