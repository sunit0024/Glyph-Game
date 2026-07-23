package com.nothinglondon.sdkdemo.demos.basic

import android.content.Context
import androidx.core.content.ContextCompat
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import com.nothing.ketchum.GlyphMatrixUtils
import com.nothinglondon.sdkdemo.R
import com.nothinglondon.sdkdemo.demos.GlyphMatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BasicDemoService : GlyphMatrixService("Basic-Demo") {

    private lateinit var bgScope: CoroutineScope
    private val _counterFlow = MutableStateFlow(0)
    private var waitTimerJob: Job? = null

    @OptIn(FlowPreview::class)
    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        val iconObject = GlyphMatrixObject.Builder().setImageSource(
            GlyphMatrixUtils.drawableToBitmap(
                ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
            )
        )
            .setScale(100)
            .setOrientation(0)
            .setPosition(0, 0)
            .setReverse(false)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(iconObject)
            .build(applicationContext)

        glyphMatrixManager.setMatrixFrame(frame.render())

        bgScope = CoroutineScope(Dispatchers.Default)
        bgScope.launch {
            _counterFlow.collect(::postCounter)
        }
    }

    override fun performOnServiceDisconnected(context: Context) {
        super.performOnServiceDisconnected(context)
        bgScope.cancel()
    }

    private fun postCounter(value: Int) {
        if (value == 0) return
        glyphMatrixManager?.apply {
            val textObject = GlyphMatrixObject.Builder()
                .setText(value.toString())
                .setPosition(10, 10)
                .build()

            val frame = GlyphMatrixFrame.Builder()
                .addTop(textObject)
                .build(applicationContext)

            setMatrixFrame(frame.render())
        }
    }

    override fun onTouchPointPressed() {
        waitTimerJob?.cancel()
        waitTimerJob = bgScope.launch {
            while (isActive) {
                delay(500L)
                val value = _counterFlow.value + 1
                _counterFlow.value = value
            }
        }
    }

    override fun onTouchPointReleased() {
        waitTimerJob?.cancel()
        waitTimerJob = null
    }

}