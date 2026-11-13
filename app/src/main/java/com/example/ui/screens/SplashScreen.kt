package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.SyncBeatBackground
import com.example.ui.components.SyncBeatLogoMark
import com.example.ui.theme.ElectricPurple
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VividViolet
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    isAppReady: Boolean,
    onFinished: () -> Unit
) {
    val scale = remember { Animatable(0.75f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(650, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(550))
    }
    LaunchedEffect(isAppReady) {
        if (isAppReady) {
            delay(1300L)
            onFinished()
        }
    }

    SyncBeatBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .scale(scale.value)
                .alpha(alpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SyncBeatLogoMark(size = 112.dp, animated = true)
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 40.sp,
                    letterSpacing = 4.sp,
                    brush = Brush.linearGradient(listOf(NeonBlue, ElectricPurple, VividViolet))
                ),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                letterSpacing = 1.sp
            )
        }
    }
}
