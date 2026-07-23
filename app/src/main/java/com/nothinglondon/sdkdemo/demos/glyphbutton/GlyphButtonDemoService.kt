package com.nothinglondon.sdkdemo.demos.glyphbutton

import android.content.Context
import com.nothing.ketchum.GlyphMatrixManager
import com.nothinglondon.sdkdemo.demos.GlyphMatrixService
import kotlin.random.Random

class GlyphButtonDemoService : GlyphMatrixService("Glyph-Button-Demo") {

    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        generateRandomPattern(glyphMatrixManager)
    }

    override fun onTouchPointLongPress() {
        glyphMatrixManager?.let {
            generateRandomPattern(it)
        }
    }

    private fun generateRandomPattern(glyphMatrixManager: GlyphMatrixManager) {
        val array = IntArray(GLYPH_ARRAY_SIZE) { Random.nextInt(RANDOM_UPPER_BOUND) }
        glyphMatrixManager.setMatrixFrame(array)
    }

    private companion object {
        private const val GLYPH_WIDTH = 25
        private const val GLYPH_HEIGHT = 25
        private const val GLYPH_ARRAY_SIZE = GLYPH_WIDTH * GLYPH_HEIGHT
        private const val RANDOM_UPPER_BOUND = 2047
    }

}