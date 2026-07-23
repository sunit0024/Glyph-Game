package com.nothinglondon.sdkdemo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothinglondon.sdkdemo.ui.theme.LedGlow
import com.nothinglondon.sdkdemo.ui.theme.LedOff
import com.nothinglondon.sdkdemo.ui.theme.LedOn
import com.nothinglondon.sdkdemo.ui.theme.NothingAmber
import com.nothinglondon.sdkdemo.ui.theme.NothingBlack
import com.nothinglondon.sdkdemo.ui.theme.NothingBorder
import com.nothinglondon.sdkdemo.ui.theme.NothingCard
import com.nothinglondon.sdkdemo.ui.theme.NothingDimGray
import com.nothinglondon.sdkdemo.ui.theme.NothingGray
import com.nothinglondon.sdkdemo.ui.theme.NothingGreen
import com.nothinglondon.sdkdemo.ui.theme.NothingRed
import com.nothinglondon.sdkdemo.ui.theme.NothingSurface
import com.nothinglondon.sdkdemo.ui.theme.NothingAndroidSDKDemoTheme
import com.nothinglondon.sdkdemo.ui.theme.NothingWhite
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NothingAndroidSDKDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NothingBlack
                ) {
                    GlyphPongDashboard()
                }
            }
        }
    }
}

// ─── Embedded Game Engine (mirrors the service logic for on-screen play) ────

private const val W = 25
private const val H = 25
private const val PADDLE_WIDTH = 5
private const val INITIAL_SPEED = 0.45f
private const val SPEED_INC = 0.03f
private const val MAX_SPEED = 1.0f
private const val BRICK_ROWS = 4
private const val FRAME_MS = 33L
private const val AUTO_LAUNCH_FRAMES = 60 // 2 seconds
private const val TILT_DEAD = 0.8f
private const val TILT_SCALE = 2.5f
private const val MAX_BRIGHT = 4095

private enum class GState { READY, PLAYING, GAME_OVER, WIN }
enum class PowerUpType { EXPAND, SHRINK, MULTI_BALL, LASER }
data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float)
data class PowerUpDrop(var x: Float, var y: Float, val type: PowerUpType)
data class Laser(var x: Int, var y: Int)

@Composable
fun GlyphPongDashboard() {
    val context = LocalContext.current

    // ── Game state ──
    var state by remember { mutableStateOf(GState.READY) }
    val balls = remember { mutableListOf<Ball>() }
    val powerUps = remember { mutableListOf<PowerUpDrop>() }
    val lasers = remember { mutableListOf<Laser>() }
    var activePowerUp by remember { mutableStateOf<PowerUpType?>(null) }
    var powerUpTimer by remember { mutableIntStateOf(0) }
    var paddleWidth by remember { mutableIntStateOf(PADDLE_WIDTH) }
    var speed by remember { mutableFloatStateOf(INITIAL_SPEED) }
    var paddleX by remember { mutableFloatStateOf((W - PADDLE_WIDTH) / 2f) }
    var bricks by remember { mutableStateOf(Array(BRICK_ROWS) { BooleanArray(W) { true } }) }
    var bricksLeft by remember { mutableIntStateOf(BRICK_ROWS * W) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }
    var tilt by remember { mutableFloatStateOf(0f) }
    var grid by remember { mutableStateOf(IntArray(W * H)) }

    // Vibrator
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun vibrateShort() {
        try { vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) }
        catch (_: Exception) {}
    }
    fun vibrateLong() {
        try { vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)) }
        catch (_: Exception) {}
    }

    fun resetGame() {
        state = GState.READY
        bricks = Array(BRICK_ROWS) { BooleanArray(W) { true } }
        bricksLeft = BRICK_ROWS * W
        score = 0
        speed = INITIAL_SPEED
        paddleWidth = PADDLE_WIDTH
        paddleX = (W - paddleWidth) / 2f
        balls.clear()
        powerUps.clear()
        lasers.clear()
        activePowerUp = null
        powerUpTimer = 0
        frame = 0
    }

    // ── Accelerometer ──
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                e?.let { tilt = -it.values[0] }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }

    // ── Game loop ──
    LaunchedEffect(Unit) {
        while (true) {
            frame++
            val adjTilt = if (abs(tilt) < TILT_DEAD) 0f
                else (tilt - TILT_DEAD * if (tilt > 0) 1f else -1f) * TILT_SCALE

            when (state) {
                GState.READY -> {
                    paddleX = (paddleX + adjTilt * 0.3f).coerceIn(0f, (W - paddleWidth).toFloat())

                    // Auto-launch after 2 seconds
                    if (frame >= AUTO_LAUNCH_FRAMES && state == GState.READY) {
                        state = GState.PLAYING
                        val co = (paddleX + paddleWidth / 2f) - W / 2f
                        val startVx = (co / W) * 0.5f + 0.3f
                        balls.add(Ball(paddleX + paddleWidth / 2f, H - 3f, startVx, -speed))
                        vibrateShort()
                    }
                }
                GState.PLAYING -> {
                    paddleX = (paddleX + adjTilt * 0.3f).coerceIn(0f, (W - paddleWidth).toFloat())
                    
                    // Update Balls
                    val ballIt = balls.iterator()
                    while (ballIt.hasNext()) {
                        val ball = ballIt.next()
                        ball.x += ball.vx
                        ball.y += ball.vy

                        // Wall collisions
                        if (ball.x <= 0f) { ball.x = 0f; ball.vx = abs(ball.vx) }
                        if (ball.x >= W - 1f) { ball.x = W - 1f; ball.vx = -abs(ball.vx) }
                        if (ball.y <= 0f) { ball.y = 0f; ball.vy = abs(ball.vy) }

                        // Paddle collision
                        val py = H - 2
                        if (ball.vy > 0 && ball.y >= py - 0.5f && ball.y <= py + 0.5f) {
                            val pL = paddleX; val pR = paddleX + paddleWidth
                            if (ball.x >= pL - 0.5f && ball.x <= pR + 0.5f) {
                                val hit = (ball.x - pL) / paddleWidth
                                ball.vx = (hit - 0.5f) * 1.2f
                                ball.vy = -abs(ball.vy)
                                ball.y = py - 1f
                                speed = (speed + SPEED_INC).coerceAtMost(MAX_SPEED)
                                val r = speed / sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
                                ball.vx *= r; ball.vy *= r
                                vibrateShort()
                            }
                        }

                        // Bottom — ball lost
                        if (ball.y >= H) {
                            ballIt.remove()
                            continue
                        }

                        // Brick collisions
                        val bx = ball.x.roundToInt().coerceIn(0, W - 1)
                        val by = ball.y.roundToInt()
                        for (row in 0 until BRICK_ROWS) {
                            val brickY = 1 + row
                            if (by == brickY && bx in 0 until W && bricks[row][bx]) {
                                // Create new copy so Compose recomposes
                                val newBricks = Array(BRICK_ROWS) { r -> bricks[r].copyOf() }
                                newBricks[row][bx] = false
                                bricks = newBricks
                                bricksLeft--
                                score += (BRICK_ROWS - row) * 10

                                val prevBy = (ball.y - ball.vy).roundToInt()
                                if (prevBy != brickY) ball.vy = -ball.vy else ball.vx = -ball.vx
                                vibrateShort()

                                // Spawn Power-Up (15% chance)
                                if (Math.random() < 0.15) {
                                    val types = PowerUpType.entries.toTypedArray()
                                    val type = types[(Math.random() * types.size).toInt()]
                                    powerUps.add(PowerUpDrop(bx.toFloat(), by.toFloat(), type))
                                }

                                if (bricksLeft <= 0) {
                                    state = GState.WIN
                                    if (score > highScore) highScore = score
                                    vibrateLong()
                                }
                                break
                            }
                        }
                    }

                    if (balls.isEmpty() && state == GState.PLAYING) {
                        state = GState.GAME_OVER
                        if (score > highScore) highScore = score
                        vibrateLong()
                    }
                    
                    // Update Power-Ups
                    if (powerUpTimer > 0) {
                        powerUpTimer--
                        if (powerUpTimer <= 0) {
                            activePowerUp = null
                            paddleWidth = PADDLE_WIDTH
                        }
                    }
                    
                    val py = H - 2
                    val puIt = powerUps.iterator()
                    while (puIt.hasNext()) {
                        val p = puIt.next()
                        p.y += 0.2f
                        if (p.y >= py && p.y <= py + 1f) {
                            if (p.x >= paddleX - 0.5f && p.x <= paddleX + paddleWidth + 0.5f) {
                                when (p.type) {
                                    PowerUpType.EXPAND -> { paddleWidth = 7; activePowerUp = p.type; powerUpTimer = 150 }
                                    PowerUpType.SHRINK -> { paddleWidth = 3; activePowerUp = p.type; powerUpTimer = 150 }
                                    PowerUpType.MULTI_BALL -> {
                                        if (balls.isNotEmpty()) {
                                            val b = balls[0]
                                            balls.add(Ball(b.x, b.y, -b.vx, b.vy))
                                        } else {
                                            balls.add(Ball(paddleX + paddleWidth / 2f, H - 3f, 0.35f, -speed))
                                        }
                                        activePowerUp = p.type; powerUpTimer = 30
                                    }
                                    PowerUpType.LASER -> { paddleWidth = PADDLE_WIDTH; activePowerUp = p.type; powerUpTimer = 150 }
                                }
                                vibrateShort()
                                puIt.remove()
                                continue
                            }
                        }
                        if (p.y >= H) puIt.remove()
                    }
                    
                    // Update Lasers
                    if (activePowerUp == PowerUpType.LASER && frame % 15 == 0) {
                        val left = paddleX.roundToInt()
                        val leftLaserX = left + 1
                        val rightLaserX = left + paddleWidth - 2
                        lasers.add(Laser(leftLaserX, H - 3))
                        if (leftLaserX != rightLaserX) {
                            lasers.add(Laser(rightLaserX, H - 3))
                        }
                    }
                    val laserIt = lasers.iterator()
                    while (laserIt.hasNext()) {
                        val l = laserIt.next()
                        l.y -= 1
                        if (l.y < 0) { laserIt.remove(); continue }
                        var hit = false
                        for (row in 0 until BRICK_ROWS) {
                            val brickY = 1 + row
                            if (l.y == brickY && l.x in 0 until W && bricks[row][l.x]) {
                                val newBricks = Array(BRICK_ROWS) { r -> bricks[r].copyOf() }
                                newBricks[row][l.x] = false
                                bricks = newBricks
                                bricksLeft--
                                score += (BRICK_ROWS - row) * 10
                                hit = true
                                vibrateShort()
                                if (bricksLeft <= 0) {
                                    state = GState.WIN
                                    if (score > highScore) highScore = score
                                    vibrateLong()
                                }
                                break
                            }
                        }
                        if (hit) laserIt.remove()
                    }
                }
                GState.GAME_OVER, GState.WIN -> { /* flashCounter handled via frame */ }
            }

            // ── Render grid ──
            val g = IntArray(W * H)
            when (state) {
                GState.READY -> {
                    renderBricksTo(g, bricks)
                    renderPaddleTo(g, paddleX, paddleWidth)
                    val pulse = (MAX_BRIGHT * (0.5f + 0.5f * sin(frame * 0.15f))).toInt().coerceIn(0, MAX_BRIGHT)
                    setPixelSafe(g, (paddleX + paddleWidth / 2f).roundToInt(), H - 3, pulse)
                }
                GState.PLAYING -> {
                    renderBricksTo(g, bricks)
                    renderPaddleTo(g, paddleX, paddleWidth)
                    
                    for (ball in balls) {
                        setPixelSafe(g, ball.x.roundToInt(), ball.y.roundToInt(), MAX_BRIGHT)
                    }
                    if (frame % 4 < 2) {
                        for (p in powerUps) {
                            setPixelSafe(g, p.x.roundToInt(), p.y.roundToInt(), 2000)
                        }
                    }
                    for (l in lasers) {
                        setPixelSafe(g, l.x, l.y, MAX_BRIGHT)
                    }
                }
                GState.GAME_OVER -> {
                    if (frame % 10 < 5) {
                        for (i in 0 until W.coerceAtMost(H)) {
                            setPixelSafe(g, i, i, MAX_BRIGHT)
                            setPixelSafe(g, W - 1 - i, i, MAX_BRIGHT)
                        }
                    }
                }
                GState.WIN -> {
                    val ring = (frame / 3) % (W / 2 + 5)
                    val cx = W / 2; val cy = H / 2
                    for (y in 0 until H) for (x in 0 until W) {
                        val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()).toInt()
                        if (d in ring..(ring + 2)) g[y * W + x] = MAX_BRIGHT
                    }
                }
            }
            grid = g

            delay(FRAME_MS)
        }
    }

    // ── UI ──
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            "GLYPH PONG",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = NothingWhite,
            letterSpacing = 6.sp
        )
        Text(
            "BRICK BREAKER",
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.Monospace,
            color = NothingGray,
            letterSpacing = 4.sp
        )

        Spacer(Modifier.height(20.dp))

        // LED Matrix Simulator
        GlyphMatrixSimulator(
            grid = grid,
            onTap = {
                when (state) {
                    GState.READY -> {
                        state = GState.PLAYING
                        val co = (paddleX + paddleWidth / 2f) - W / 2f
                        val startVx = (co / W) * 0.5f + 0.3f
                        balls.add(Ball(paddleX + paddleWidth / 2f, H - 3f, startVx, -speed))
                        vibrateShort()
                    }
                    GState.GAME_OVER, GState.WIN -> {
                        resetGame()
                        vibrateShort()
                    }
                    else -> {}
                }
            }
        )

        Spacer(Modifier.height(20.dp))

        // State indicator
        val stateLabel = when (state) {
            GState.READY -> "LAUNCHING IN ${((AUTO_LAUNCH_FRAMES - frame).coerceAtLeast(0) * FRAME_MS / 1000 + 1).toInt()}s · TAP TO LAUNCH"
            GState.PLAYING -> "PLAYING"
            GState.GAME_OVER -> "GAME OVER · TAP TO RETRY"
            GState.WIN -> "YOU WIN! · TAP TO RESTART"
        }
        val stateColor by animateColorAsState(
            when (state) {
                GState.READY -> NothingAmber
                GState.PLAYING -> NothingGreen
                GState.GAME_OVER -> NothingRed
                GState.WIN -> NothingGreen
            },
            label = "stateColor"
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Pulsing dot
            val inf = rememberInfiniteTransition(label = "pulse")
            val alpha by inf.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor.copy(alpha = alpha))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stateLabel,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = stateColor,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        // Active PowerUp Indicator
        if (activePowerUp != null) {
            Text(
                "POWER-UP: ${activePowerUp?.name}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = NothingAmber,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // Stats cards
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard("SCORE", score.toString(), NothingWhite, Modifier.weight(1f))
            StatCard("BRICKS", bricksLeft.toString(), NothingAmber, Modifier.weight(1f))
            StatCard("BEST", highScore.toString(), NothingGreen, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard("SPEED", String.format("%.0f%%", speed / MAX_SPEED * 100), NothingRed, Modifier.weight(1f))
            StatCard("TILT", String.format("%.1f", tilt), NothingGray, Modifier.weight(1f))
            StatCard("FPS", "30", NothingDimGray, Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        // How to play card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NothingSurface)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "HOW TO PLAY",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = NothingWhite,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(16.dp))
                HowToItem("📱", "Tilt your phone left/right to move the paddle")
                HowToItem("👆", "Tap the matrix to launch the ball")
                HowToItem("🧱", "Break all bricks to win — top rows are worth more!")
                HowToItem("💀", "Miss the ball and it's game over")

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = NothingBorder)
                Spacer(Modifier.height(16.dp))

                Text(
                    "GLYPH TOY SETUP",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = NothingGray,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(12.dp))
                SetupStep("1", "Enable Glyph debug via ADB")
                SetupStep("2", "Open Settings → Glyph Interface → Glyph Toys")
                SetupStep("3", "Move \"Glyph Pong\" to Active")
                SetupStep("4", "Press the Glyph button to cycle to Pong")
                SetupStep("5", "Tilt to play — long press to restart")
            }
        }

        Spacer(Modifier.height(32.dp))

        // Footer
        Text(
            "Built with Nothing Glyph Matrix SDK",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = NothingDimGray,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ─── LED Matrix Simulator ────────────────────────────────────────────────────

@Composable
fun GlyphMatrixSimulator(grid: IntArray, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, NothingBorder, RoundedCornerShape(20.dp))
            .background(Color(0xFF0D0D0D))
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        // Glow layer behind
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val cellW = size.width / W
            val cellH = size.height / H
            val glowRadius = cellW * 0.8f

            for (y in 0 until H) {
                for (x in 0 until W) {
                    val brightness = grid.getOrElse(y * W + x) { 0 }
                    if (brightness > 200) {
                        val norm = (brightness.toFloat() / MAX_BRIGHT).coerceIn(0f, 1f)
                        val cx = x * cellW + cellW / 2
                        val cy = y * cellH + cellH / 2
                        drawCircle(
                            color = Color.White.copy(alpha = norm * 0.15f),
                            radius = glowRadius,
                            center = Offset(cx, cy),
                        )
                    }
                }
            }
        }

        // LED dots layer
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val cellW = size.width / W
            val cellH = size.height / H
            val dotRadius = cellW * 0.32f

            for (y in 0 until H) {
                for (x in 0 until W) {
                    val brightness = grid.getOrElse(y * W + x) { 0 }
                    val norm = (brightness.toFloat() / MAX_BRIGHT).coerceIn(0f, 1f)
                    val cx = x * cellW + cellW / 2
                    val cy = y * cellH + cellH / 2

                    // Off-state dot (very dim)
                    drawCircle(
                        color = if (norm > 0.01f) Color.White.copy(alpha = 0.6f + norm * 0.4f)
                                else Color.White.copy(alpha = 0.04f),
                        radius = dotRadius,
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

// ─── Stat Card ───────────────────────────────────────────────────────────────

@Composable
fun StatCard(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NothingSurface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = NothingGray,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

// ─── Info Items ──────────────────────────────────────────────────────────────

@Composable
fun HowToItem(emoji: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = NothingGray,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun SetupStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(NothingCard)
                .border(1.dp, NothingBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NothingWhite
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = NothingGray,
            lineHeight = 16.sp
        )
    }
}

// ─── Rendering Helpers ───────────────────────────────────────────────────────

private fun renderBricksTo(g: IntArray, bricks: Array<BooleanArray>) {
    val brights = intArrayOf(2800, 2200, 1600, 1200)
    for (row in 0 until BRICK_ROWS) {
        val y = 1 + row
        val b = brights[row.coerceAtMost(brights.size - 1)]
        for (x in 0 until W) {
            if (bricks[row][x]) setPixelSafe(g, x, y, b)
        }
    }
}

private fun renderPaddleTo(g: IntArray, paddleX: Float, paddleWidth: Int) {
    val y = H - 2
    val left = paddleX.roundToInt().coerceIn(0, W - paddleWidth)
    for (x in left until (left + paddleWidth).coerceAtMost(W)) {
        setPixelSafe(g, x, y, 3200)
    }
}

private fun setPixelSafe(g: IntArray, x: Int, y: Int, brightness: Int) {
    val cx = x.coerceIn(0, W - 1)
    val cy = y.coerceIn(0, H - 1)
    g[cy * W + cx] = brightness
}