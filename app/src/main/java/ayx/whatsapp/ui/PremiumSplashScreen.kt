package ayx.whatsapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ayx.whatsapp.R
import kotlinx.coroutines.delay

// Premium splash: gradient background, animated logo, shimmer effect
// 144fps optimized: only animates alpha/scale (GPU-accelerated), no layout changes

private val SplashGradient = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0B141A),
        Color(0xFF111B21),
        Color(0xFF0B141A),
    )
)

private val AyxGreen = Color(0xFF00A884)
private val AyxPurple = Color(0xFF7C3AED)

@Composable
fun PremiumSplashScreen(onComplete: () -> Unit) {
    var logoVisible by remember { mutableStateOf(false) }
    var textVisible by remember { mutableStateOf(false) }

    // Staggered entrance
    LaunchedEffect(Unit) {
        delay(100)
        logoVisible = true
        delay(300)
        textVisible = true
        delay(1800)
        onComplete()
    }

    // Logo scale animation (GPU-accelerated)
    val logoScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0.7f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "logoScale"
    )
    val logoAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0f,
        animationSpec = tween(500),
        label = "logoAlpha"
    )
    val textAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (textVisible) 1f else 0f,
        animationSpec = tween(500),
        label = "textAlpha"
    )

    // Subtle pulsing glow (infinite, GPU-accelerated)
    val infiniteTransition = rememberInfiniteTransition(label = "splashPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Logo with scale + alpha + pulse
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(logoScale * pulse)
                    .alpha(logoAlpha)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AyxGreen, AyxGreen.copy(alpha = 0.7f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // AyX logo or icon
                Text(
                    text = "AyX",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(24.dp))

            // App name with fade
            Text(
                text = "WhatsAyX",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(textAlpha)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Fast • Secure • Premium",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }

        // Bottom branding
        Text(
            text = "by AyX",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .alpha(textAlpha)
        )
    }
}

// 144fps page transition specs - use these for all screen navigations
object AyxAnimations {
    // Fast fade (180ms) - for quick UI updates
    val FastFade = tween<Float>(180)
    
    // Smooth slide (250ms) - for page transitions  
    val PageSlide = tween<Int>(250, easing = FastOutSlowInEasing)
    
    // Springy scale (300ms) - for dialogs/popups
    val PopupScale = tween<Float>(300, easing = FastOutSlowInEasing)
    
    // Staggered list items (delay by index * 30ms for 144fps cascade)
    fun staggerDelay(index: Int): Int = (index * 30).coerceAtMost(300)
}
