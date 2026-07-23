package com.nothinglondon.sdkdemo.demos.animation

import android.content.Context
import com.nothing.ketchum.GlyphMatrixManager
import com.nothinglondon.sdkdemo.demos.GlyphMatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

class AnimationDemoService : GlyphMatrixService("Animation-Demo") {

    private val backgroundScope = CoroutineScope(Dispatchers.IO)
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var frame = 0

    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        backgroundScope.launch {
            while (isActive) {
                val array = generateNextAnimationFrame()
                uiScope.launch {
                    glyphMatrixManager.setMatrixFrame(array)
                }
                // wait a bit
                delay(30)
                // next frame
                frame++
                if (frame >= WIDTH) {
                    frame = 0
                }
            }
        }
    }

    override fun performOnServiceDisconnected(context: Context) {
        backgroundScope.cancel()
    }

    private fun generateNextAnimationFrame(): IntArray {
        val shiftAmount = ANGLE_PER_PIXEL_DEGREES * frame
        val grid = Array(HEIGHT * WIDTH) { 0 }
        for (i in 0..<HEIGHT) {
            val angle = Math.toRadians(i * ANGLE_PER_PIXEL_DEGREES + shiftAmount)
            val value = sin(angle)
            val row = (value * HALF_HEIGHT).toInt() + MID_POINT
            grid[row * WIDTH + i] = 255
        }
        return grid.toIntArray()
    }

    private companion object {
        private const val WIDTH = 25
        private const val HEIGHT = 25
        private const val HALF_HEIGHT = HEIGHT.toDouble() / 2
        private const val MID_POINT = HEIGHT / 2
        private const val ANGLE_PER_PIXEL_DEGREES = 360.0 / WIDTH
    }

}