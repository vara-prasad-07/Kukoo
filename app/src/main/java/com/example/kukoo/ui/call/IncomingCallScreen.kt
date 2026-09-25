package com.example.kukoo.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kukoo.call.CallRinger
import com.example.kukoo.domain.TimeFormat
import com.example.kukoo.ui.common.LightSystemBarIcons
import com.example.kukoo.ui.theme.CallAnswer
import com.example.kukoo.ui.theme.CallBackgroundBottom
import com.example.kukoo.ui.theme.CallBackgroundTop
import com.example.kukoo.ui.theme.CallDecline
import com.example.kukoo.ui.theme.Indigo40
import kotlinx.coroutines.delay
import java.time.Clock

private const val RING_TIMEOUT_MS = 30_000L

/** Screen 2 of 4: full-screen incoming call from "Your Tasks". */
@Composable
fun IncomingCallScreen(
    clock: Clock,
    format: TimeFormat,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    LightSystemBarIcons()

    val context = LocalContext.current
    val ringer = remember { CallRinger(context) }
    DisposableEffect(Unit) {
        ringer.start()
        onDispose { ringer.stop() }
    }
    // Unanswered calls end as a missed call.
    LaunchedEffect(Unit) {
        delay(RING_TIMEOUT_MS)
        onDecline()
    }

    var time by remember { mutableStateOf(format.clockTime(clock.millis())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = format.clockTime(clock.millis())
            delay(10_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom)))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(time, style = MaterialTheme.typography.displaySmall, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Kukoo voice call", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))

            Spacer(Modifier.weight(1f))

            PulsingAvatar()
            Spacer(Modifier.height(28.dp))
            Text("Your Tasks", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Incoming call…", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))

            Spacer(Modifier.weight(1.2f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallButton(CallDecline, Icons.Default.CallEnd, "Decline", onDecline)
                CallButton(CallAnswer, Icons.Default.Call, "Answer", onAnswer)
            }
        }
    }
}

@Composable
private fun PulsingAvatar() {
    val transition = rememberInfiniteTransition(label = "ring")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(120.dp + 80.dp * pulse)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f * (1f - pulse)))
        )
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Indigo40),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Checklist, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
private fun CallButton(color: Color, icon: ImageVector, label: String, onClick: () -> Unit) {
    // The circle and its label are one tap target.
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
    }
}
