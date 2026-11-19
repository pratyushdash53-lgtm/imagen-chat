package app.imagen.chat.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Light/dark grey squares so transparent PNGs read as transparent. */
fun Modifier.checkerboard(
    light: Color = Color(0xFFFFFFFF),
    dark: Color = Color(0xFFE3E1EA),
    cell: Dp = 12.dp,
): Modifier = drawBehind {
    val c = cell.toPx()
    drawRect(light)
    val cols = ceil(size.width / c).toInt()
    val rows = ceil(size.height / c).toInt()
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            if ((x + y) % 2 == 1) drawRect(dark, Offset(x * c, y * c), Size(c, c))
        }
    }
}

@Composable
fun rememberBitmap(file: File?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, file?.absolutePath) {
        value = file?.let { f ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
        }
    }
    return state.value
}

/** An image on a checkerboard (when it has transparency) with rounded corners. */
@Composable
fun PictureBox(
    file: File,
    hasAlpha: Boolean,
    ratio: Float,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
) {
    val bitmap = rememberBitmap(file)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(shape)
            .then(
                if (hasAlpha) Modifier.checkerboard()
                else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            ),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Generated image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private val Aurora1 = Color(0xFF7C5CFF)
private val Aurora2 = Color(0xFFFF6FB5)
private val Aurora3 = Color(0xFF38D5E8)
private val Aurora4 = Color(0xFFFFC857)
private val AuroraBase = Color(0xFF14121F)

/**
 * The placeholder shown while an image is being generated: drifting aurora
 * orbs, a soft shimmer sweep and twinkling sparkles behind the status text.
 */
@Composable
fun GeneratingCard(
    stage: String,
    ratio: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "generating")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds++
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(AuroraBase),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val m = min(w, h)
            val tau = (2 * Math.PI).toFloat()

            fun orb(color: Color, kx: Int, ky: Int, phase: Float, radius: Float, alpha: Float) {
                val cx = w * (0.5f + 0.30f * sin(tau * kx * t + phase))
                val cy = h * (0.5f + 0.30f * cos(tau * ky * t + phase * 1.3f))
                val center = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            orb(Aurora1, 1, 1, 0f, m * 0.75f, 0.80f)
            orb(Aurora2, 1, 2, 2.1f, m * 0.62f, 0.65f)
            orb(Aurora3, 2, 1, 4.2f, m * 0.68f, 0.60f)
            orb(Aurora4, 1, 1, 5.3f, m * 0.40f, 0.35f)

            // Diagonal shimmer sweeping across the card.
            val x = (-0.6f + 2.2f * t) * w
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + 0.5f * w, 0.35f * h),
                ),
            )

            // Twinkling sparkles at fixed pseudo-random positions.
            for (i in 0 until 16) {
                val fx = (i * 0.618034f * 7f) % 1f
                val fy = (i * 0.381966f * 11f + 0.13f) % 1f
                val a = abs(sin(tau * (t * 3 + i / 16f)))
                drawCircle(
                    color = Color.White.copy(alpha = a * 0.9f),
                    radius = (1.5f + (i % 3)) * density,
                    center = Offset(fx * w, fy * h),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.32f)) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Crossfade(targetState = stage, label = "stage") { label ->
                        Text(label, color = Color.White, fontSize = 14.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 3) {
                            val a = (pulse + i * 0.22f).coerceAtMost(1f)
                            Box(
                                Modifier
                                    .padding(vertical = 1.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = a))
                                    .padding(3.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = "${seconds}s · this can take up to a minute",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun SmallPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ClickableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
}
