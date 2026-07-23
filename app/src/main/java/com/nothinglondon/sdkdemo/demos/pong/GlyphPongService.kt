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

    private var state = State.READY

    // Ball — floating-point for smooth sub-pixel movement
    private var ballX = W / 2f
    private var ballY = H - 3f
    private var ballVx = 0.35f
    private var ballVy = -INITIAL_BALL_SPEED
    private var currentSpeed = INITIAL_BALL_SPEED

    // Paddle
    private var paddleX = (W - PADDLE_WIDTH) / 2f // left edge of paddle
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
        val centerOffset = (paddleX + PADDLE_WIDTH / 2f) - W / 2f
        ballVx = (centerOffset / W) * 0.5f + 0.3f
        ballVy = -currentSpeed
        vibrateShort()
    }

    // ─── Game Logic ──────────────────────────────────────────────────────

    private fun resetGame() {
        state = State.READY
        bricks = Array(BRICK_ROWS) { BooleanArray(W) { true } }
        bricksRemaining = BRICK_ROWS * W
        score = 0
        currentSpeed = INITIAL_BALL_SPEED
        paddleX = (W - PADDLE_WIDTH) / 2f
        ballX = W / 2f
        ballY = H - 3f
        ballVx = 0.35f
        ballVy = -currentSpeed
        frameCount = 0
        flashCounter = 0
        autoLaunchCounter = 0
    }

    private fun tick() {
        frameCount++

        when (state) {
            State.READY -> {
                // Move paddle with tilt, ball sits on paddle
                updatePaddle()
                ballX = paddleX + PADDLE_WIDTH / 2f
                ballY = H - 3f

                // Auto-launch countdown
                autoLaunchCounter++
                if (autoLaunchCounter >= AUTO_LAUNCH_FRAMES) {
                    launchBall()
                }
            }

            State.PLAYING -> {
                updatePaddle()
                updateBall()
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
        paddleX = paddleX.coerceIn(0f, (W - PADDLE_WIDTH).toFloat())
    }

    private fun updateBall() {
        // Move ball
        ballX += ballVx
        ballY += ballVy

        // ── Wall collisions ──
        // Left wall
        if (ballX <= 0f) {
            ballX = 0f
            ballVx = abs(ballVx)
        }
        // Right wall
        if (ballX >= W - 1f) {
            ballX = W - 1f
            ballVx = -abs(ballVx)
        }
        // Ceiling
        if (ballY <= 0f) {
            ballY = 0f
            ballVy = abs(ballVy)
        }

        // ── Paddle collision ──
        val paddleY = H - 2 // paddle sits on row H-2
        if (ballVy > 0 && ballY >= paddleY - 0.5f && ballY <= paddleY + 0.5f) {
            val paddleLeft = paddleX
            val paddleRight = paddleX + PADDLE_WIDTH
            if (ballX >= paddleLeft - 0.5f && ballX <= paddleRight + 0.5f) {
                // Calculate bounce angle based on where ball hits paddle
                val hitPos = (ballX - paddleLeft) / PADDLE_WIDTH // 0..1
                ballVx = (hitPos - 0.5f) * 1.2f // deflect left/right
                ballVy = -abs(ballVy)
                ballY = paddleY - 1f

                // Slight speed increase
                currentSpeed = (currentSpeed + SPEED_INCREMENT).coerceAtMost(MAX_BALL_SPEED)
                val speed = currentSpeed
                val ratio = speed / kotlin.math.sqrt(ballVx * ballVx + ballVy * ballVy)
                ballVx *= ratio
                ballVy *= ratio

                vibrateShort()
            }
        }

        // ── Bottom boundary — ball lost ──
        if (ballY >= H) {
            state = State.GAME_OVER
            vibrateLong()
            return
        }

        // ── Brick collisions ──
        val bx = ballX.roundToInt().coerceIn(0, W - 1)
        val by = ballY.roundToInt()

        for (row in 0 until BRICK_ROWS) {
            val brickY = BRICK_TOP_OFFSET + row * BRICK_HEIGHT
            if (by == brickY && bx in 0 until W && bricks[row][bx]) {
                bricks[row][bx] = false
                bricksRemaining--
                score += (BRICK_ROWS - row) * 10 // top rows worth more

                // Determine collision direction
                val prevBy = (ballY - ballVy).roundToInt()
                if (prevBy != brickY) {
                    ballVy = -ballVy // vertical hit
                } else {
                    ballVx = -ballVx // horizontal hit
                }

                vibrateShort()

                // Check win
                if (bricksRemaining <= 0) {
                    state = State.WIN
                    vibrateLong()
                }
                break // only destroy one brick per frame
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
                setPixel(grid, ballX.roundToInt(), ballY.roundToInt(), pulse)
            }

            State.PLAYING -> {
                renderBricks(grid)
                renderPaddle(grid)
                setPixel(grid, ballX.roundToInt(), ballY.roundToInt(), BRIGHT_BALL)
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
                // else: all off (flash)
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
        val left = paddleX.roundToInt().coerceIn(0, W - PADDLE_WIDTH)
        for (x in left until (left + PADDLE_WIDTH).coerceAtMost(W)) {
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
