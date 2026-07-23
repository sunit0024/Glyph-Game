package com.nothinglondon.sdkdemo.demos.pong

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.nothing.ketchum.GlyphMatrixManager
import com.nothinglondon.sdkdemo.demos.GlyphMatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GlyphPongService — A brick-breaker / pong game running entirely on the
 * Nothing Phone (3) Glyph Matrix (25x25 LED grid).
 *
 * Controls:
 *   • Tilt the phone left/right → paddle moves horizontally
 *   • Ball auto-launches after a 2-second countdown when the toy activates
 *   • Long press on Glyph Button → restart game
 *
 * LED brightness values: 0 = off, up to 4095 max.
 * We use distinct brightness levels for different game elements to give
 * a visual "depth" effect on the monochrome LED matrix.
 */
class GlyphPongService : GlyphMatrixService("Glyph-Pong") {

    // ─── Constants ───────────────────────────────────────────────────────
    companion object {
        private const val TAG = "GlyphPong"

        // Matrix dimensions for Nothing Phone (3)
        const val W = 25
        const val H = 25

        // Brightness levels
        private const val BRIGHT_BALL = 4095
        private const val BRIGHT_PADDLE = 3200
        private const val BRIGHT_BRICK_ROW1 = 2800
        private const val BRIGHT_BRICK_ROW2 = 2200
        private const val BRIGHT_BRICK_ROW3 = 1600
        private const val BRIGHT_BRICK_ROW4 = 1200

        // Game parameters
        private const val PADDLE_WIDTH = 5
        private const val INITIAL_BALL_SPEED = 0.45f
        private const val SPEED_INCREMENT = 0.03f
        private const val MAX_BALL_SPEED = 1.0f
        private const val BRICK_ROWS = 4
        private const val BRICK_HEIGHT = 1 // each brick is 1 row tall
        private const val BRICK_TOP_OFFSET = 1 // start bricks from row 1
        private const val FRAME_DELAY_MS = 33L // ~30 FPS
        private const val AUTO_LAUNCH_FRAMES = 60 // 2 seconds at 30 FPS

        // Tilt sensitivity
        private const val TILT_DEAD_ZONE = 0.8f
        private const val TILT_SCALE = 2.5f
    }

    // ─── Game State ──────────────────────────────────────────────────────
    enum class State { READY, PLAYING, GAME_OVER, WIN }
    enum class PowerUpType { EXPAND, SHRINK, MULTI_BALL, LASER }

    data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float)
    data class PowerUpDrop(var x: Float, var y: Float, val type: PowerUpType)
    data class Laser(var x: Int, var y: Int)

    private var state = State.READY

    // Balls and Power-ups
    private var balls = mutableListOf<Ball>()
    private var powerUpDrops = mutableListOf<PowerUpDrop>()
    private var lasers = mutableListOf<Laser>()
    private var currentSpeed = INITIAL_BALL_SPEED

    private var activePowerUp: PowerUpType? = null
    private var powerUpTimer = 0

    // Paddle
    private var paddleWidth = PADDLE_WIDTH
    private var paddleX = (W - paddleWidth) / 2f // left edge of paddle
    private var tiltValue = 0f // raw accelerometer x-axis

    // Bricks: true = alive
    private var bricks = Array(BRICK_ROWS) { BooleanArray(W) { true } }
    private var bricksRemaining = BRICK_ROWS * W
    private var score = 0

    // Animation counters
    private var frameCount = 0
    private var flashCounter = 0
    private var autoLaunchCounter = 0

    // ─── Android Components ──────────────────────────────────────────────
    private lateinit var bgScope: CoroutineScope
    private var sensorManager: SensorManager? = null
    private var vibrator: Vibrator? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                // event.values[0] = lateral acceleration (tilt left/right)
                // Positive = tilt left, Negative = tilt right (phone face-down for Glyph)
                // When playing with Glyph visible (phone face-down), axes are mirrored
                tiltValue = it.values[0]
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ─── Service Lifecycle ───────────────────────────────────────────────

    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        Log.d(TAG, "Service connected — initializing game")

        // Set up sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
            sensorManager?.registerListener(
                sensorListener,
                accel,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        // Set up vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        resetGame()

        // Start game loop
        bgScope = CoroutineScope(Dispatchers.Default)
        bgScope.launch {
            while (isActive) {
                try {
                    tick()
                    val frame = renderFrame()
                    // setMatrixFrame must be called from the service thread
                    launch(Dispatchers.Main) {
                        try {
                            glyphMatrixManager.setMatrixFrame(frame)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting matrix frame", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in game loop", e)
                }
                delay(FRAME_DELAY_MS)
            }
        }
    }

    override fun performOnServiceDisconnected(context: Context) {
        Log.d(TAG, "Service disconnected — cleaning up")
        sensorManager?.unregisterListener(sensorListener)
        if (::bgScope.isInitialized) bgScope.cancel()
    }

    // ─── Glyph Touch/Button Events ────────────────────────────────────────
    // NOTE: Short press on Glyph Button cycles through toys (Nothing OS behavior).
    // Ball auto-launches via countdown. Long press restarts.

    override fun onTouchPointPressed() {
        // Touch can also launch/restart
        when (state) {
            State.READY -> launchBall()
            State.GAME_OVER, State.WIN -> {
                resetGame()
                vibrateShort()
            }
            State.PLAYING -> { /* tilt controls paddle */ }
        }
    }

    override fun onTouchPointLongPress() {
        // Long press always resets the game
        resetGame()
        vibrateShort()
    }

    private fun launchBall() {
        state = State.PLAYING
        val centerOffset = (paddleX + paddleWidth / 2f) - W / 2f
        val startVx = (centerOffset / W) * 0.5f + 0.3f
        balls.add(Ball(paddleX + paddleWidth / 2f, H - 3f, startVx, -currentSpeed))
        vibrateShort()
    }

    // ─── Game Logic ──────────────────────────────────────────────────────

    private fun resetGame() {
        state = State.READY
        bricks = Array(BRICK_ROWS) { BooleanArray(W) { true } }
        bricksRemaining = BRICK_ROWS * W
        score = 0
        currentSpeed = INITIAL_BALL_SPEED
        paddleWidth = PADDLE_WIDTH
        paddleX = (W - paddleWidth) / 2f
        balls.clear()
        powerUpDrops.clear()
        lasers.clear()
        activePowerUp = null
        powerUpTimer = 0
        frameCount = 0
        flashCounter = 0
        autoLaunchCounter = 0
    }

    private fun tick() {
        frameCount++

        when (state) {
            State.READY -> {
                // Move paddle with tilt
                updatePaddle()

                // Auto-launch countdown
                autoLaunchCounter++
                if (autoLaunchCounter >= AUTO_LAUNCH_FRAMES) {
                    launchBall()
                }
            }

            State.PLAYING -> {
                updatePaddle()
                updateBalls()
                updatePowerUps()
                updateLasers()
            }

            State.GAME_OVER, State.WIN -> {
                flashCounter++
            }
        }
    }

    private fun updatePaddle() {
        // Apply tilt with dead zone
        val adjustedTilt = if (abs(tiltValue) < TILT_DEAD_ZONE) {
            0f
        } else {
            (tiltValue - TILT_DEAD_ZONE * if (tiltValue > 0) 1f else -1f) * TILT_SCALE
        }

        paddleX += adjustedTilt * 0.3f

        // Clamp paddle to screen bounds
        paddleX = paddleX.coerceIn(0f, (W - paddleWidth).toFloat())
    }

    private fun updateBalls() {
        val iterator = balls.iterator()
        while (iterator.hasNext()) {
            val ball = iterator.next()
            
            // Move ball
            ball.x += ball.vx
            ball.y += ball.vy

            // ── Wall collisions ──
            if (ball.x <= 0f) {
                ball.x = 0f
                ball.vx = abs(ball.vx)
            }
            if (ball.x >= W - 1f) {
                ball.x = W - 1f
                ball.vx = -abs(ball.vx)
            }
            if (ball.y <= 0f) {
                ball.y = 0f
                ball.vy = abs(ball.vy)
            }

            // ── Paddle collision ──
            val paddleY = H - 2
            if (ball.vy > 0 && ball.y >= paddleY - 0.5f && ball.y <= paddleY + 0.5f) {
                val paddleLeft = paddleX
                val paddleRight = paddleX + paddleWidth
                if (ball.x >= paddleLeft - 0.5f && ball.x <= paddleRight + 0.5f) {
                    val hitPos = (ball.x - paddleLeft) / paddleWidth
                    ball.vx = (hitPos - 0.5f) * 1.2f
                    ball.vy = -abs(ball.vy)
                    ball.y = paddleY - 1f

                    currentSpeed = (currentSpeed + SPEED_INCREMENT).coerceAtMost(MAX_BALL_SPEED)
                    val speed = currentSpeed
                    val ratio = speed / kotlin.math.sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
                    ball.vx *= ratio
                    ball.vy *= ratio

                    vibrateShort()
                }
            }

            // ── Bottom boundary — ball lost ──
            if (ball.y >= H) {
                iterator.remove()
                continue
            }

            // ── Brick collisions ──
            val bx = ball.x.roundToInt().coerceIn(0, W - 1)
            val by = ball.y.roundToInt()
            var hitBrick = false

            for (row in 0 until BRICK_ROWS) {
                val brickY = BRICK_TOP_OFFSET + row * BRICK_HEIGHT
                if (by == brickY && bx in 0 until W && bricks[row][bx]) {
                    bricks[row][bx] = false
                    bricksRemaining--
                    score += (BRICK_ROWS - row) * 10
                    hitBrick = true

                    val prevBy = (ball.y - ball.vy).roundToInt()
                    if (prevBy != brickY) ball.vy = -ball.vy else ball.vx = -ball.vx

                    vibrateShort()

                    // Spawn Power-Up (15% chance)
                    if (kotlin.random.Random.nextFloat() < 0.15f) {
                        val types = PowerUpType.entries.toTypedArray()
                        val type = types[kotlin.random.Random.nextInt(types.size)]
                        powerUpDrops.add(PowerUpDrop(bx.toFloat(), by.toFloat(), type))
                    }

                    if (bricksRemaining <= 0) {
                        state = State.WIN
                        vibrateLong()
                    }
                    break
                }
            }
        }

        if (balls.isEmpty() && state == State.PLAYING) {
            state = State.GAME_OVER
            vibrateLong()
        }
    }

    private fun updatePowerUps() {
        if (powerUpTimer > 0) {
            powerUpTimer--
            if (powerUpTimer <= 0) {
                activePowerUp = null
                paddleWidth = PADDLE_WIDTH
            }
        }

        val paddleY = H - 2
        val iterator = powerUpDrops.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.y += 0.2f // falling speed

            if (p.y >= paddleY && p.y <= paddleY + 1f) {
                if (p.x >= paddleX - 0.5f && p.x <= paddleX + paddleWidth + 0.5f) {
                    // Caught
                    applyPowerUp(p.type)
                    vibrateShort()
                    iterator.remove()
                    continue
                }
            }
            if (p.y >= H) {
                iterator.remove()
            }
        }
    }

    private fun applyPowerUp(type: PowerUpType) {
        when (type) {
            PowerUpType.EXPAND -> {
                paddleWidth = 7
                activePowerUp = type
                powerUpTimer = 150 // 5 seconds at 30 FPS
            }
            PowerUpType.SHRINK -> {
                paddleWidth = 3
                activePowerUp = type
                powerUpTimer = 150
            }
            PowerUpType.MULTI_BALL -> {
                if (balls.isNotEmpty()) {
                    val baseBall = balls[0]
                    balls.add(Ball(baseBall.x, baseBall.y, -baseBall.vx, baseBall.vy))
                } else {
                    balls.add(Ball(paddleX + paddleWidth / 2f, H - 3f, 0.35f, -currentSpeed))
                }
                activePowerUp = type
                powerUpTimer = 30 // brief display
            }
            PowerUpType.LASER -> {
                paddleWidth = PADDLE_WIDTH
                activePowerUp = type
                powerUpTimer = 150
            }
        }
    }

    private fun updateLasers() {
        if (activePowerUp == PowerUpType.LASER && frameCount % 15 == 0) {
            val left = paddleX.roundToInt()
            val leftLaserX = left + 1
            val rightLaserX = left + paddleWidth - 2
            lasers.add(Laser(leftLaserX, H - 3))
            if (leftLaserX != rightLaserX) {
                lasers.add(Laser(rightLaserX, H - 3))
            }
        }

        val iterator = lasers.iterator()
        while (iterator.hasNext()) {
            val laser = iterator.next()
            laser.y -= 1

            if (laser.y < 0) {
                iterator.remove()
                continue
            }

            var hit = false
            for (row in 0 until BRICK_ROWS) {
                val brickY = BRICK_TOP_OFFSET + row * BRICK_HEIGHT
                if (laser.y == brickY && laser.x in 0 until W && bricks[row][laser.x]) {
                    bricks[row][laser.x] = false
                    bricksRemaining--
                    score += (BRICK_ROWS - row) * 10
                    hit = true
                    vibrateShort()
                    if (bricksRemaining <= 0) {
                        state = State.WIN
                        vibrateLong()
                    }
                    break
                }
            }
            if (hit) {
                iterator.remove()
            }
        }
    }

    // ─── Rendering ───────────────────────────────────────────────────────

    private fun renderFrame(): IntArray {
        val grid = IntArray(W * H)

        when (state) {
            State.READY -> {
                renderBricks(grid)
                renderPaddle(grid)
                // Ball pulses on paddle to indicate "ready"
                val pulse = (BRIGHT_BALL * (0.5f + 0.5f * kotlin.math.sin(frameCount * 0.15))).toInt()
                    .coerceIn(0, BRIGHT_BALL)
                setPixel(grid, (paddleX + paddleWidth / 2f).roundToInt(), H - 3, pulse)
            }

            State.PLAYING -> {
                renderBricks(grid)
                renderPaddle(grid)
                
                // Render Balls
                for (ball in balls) {
                    setPixel(grid, ball.x.roundToInt(), ball.y.roundToInt(), BRIGHT_BALL)
                }
                
                // Render Power-Ups (Blinking)
                if (frameCount % 4 < 2) {
                    for (p in powerUpDrops) {
                        setPixel(grid, p.x.roundToInt(), p.y.roundToInt(), 2000)
                    }
                }
                
                // Render Lasers
                for (laser in lasers) {
                    setPixel(grid, laser.x, laser.y, BRIGHT_BALL)
                }
            }

            State.GAME_OVER -> {
                // Flash the entire grid
                if (flashCounter % 10 < 5) {
                    // Show score as row fills
                    val fillCols = (score.coerceAtMost(W * H) * W * H / (BRICK_ROWS * W * 10))
                        .coerceAtMost(W * H)
                    for (i in 0 until fillCols) {
                        grid[i] = 800
                    }
                    // Draw an X pattern
                    for (i in 0 until W.coerceAtMost(H)) {
                        setPixel(grid, i, i, BRIGHT_BALL)
                        setPixel(grid, W - 1 - i, i, BRIGHT_BALL)
                    }
                }
            }

            State.WIN -> {
                // Celebratory expanding ring animation
                val ring = (flashCounter / 3) % (W / 2 + 5)
                val cx = W / 2
                val cy = H / 2
                for (y in 0 until H) {
                    for (x in 0 until W) {
                        val dist = kotlin.math.sqrt(
                            ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()
                        ).toInt()
                        if (dist in ring..(ring + 2)) {
                            grid[y * W + x] = BRIGHT_BALL
                        }
                    }
                }
            }
        }

        return grid
    }

    private fun renderBricks(grid: IntArray) {
        val brightnesses = intArrayOf(
            BRIGHT_BRICK_ROW1,
            BRIGHT_BRICK_ROW2,
            BRIGHT_BRICK_ROW3,
            BRIGHT_BRICK_ROW4
        )
        for (row in 0 until BRICK_ROWS) {
            val y = BRICK_TOP_OFFSET + row * BRICK_HEIGHT
            val brightness = brightnesses[row.coerceAtMost(brightnesses.size - 1)]
            for (x in 0 until W) {
                if (bricks[row][x]) {
                    setPixel(grid, x, y, brightness)
                }
            }
        }
    }

    private fun renderPaddle(grid: IntArray) {
        val y = H - 2
        val left = paddleX.roundToInt().coerceIn(0, W - paddleWidth)
        for (x in left until (left + paddleWidth).coerceAtMost(W)) {
            setPixel(grid, x, y, BRIGHT_PADDLE)
        }
    }

    private fun setPixel(grid: IntArray, x: Int, y: Int, brightness: Int) {
        val cx = x.coerceIn(0, W - 1)
        val cy = y.coerceIn(0, H - 1)
        grid[cy * W + cx] = brightness
    }

    // ─── Haptics ─────────────────────────────────────────────────────────

    private fun vibrateShort() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }

    private fun vibrateLong() {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }
}
