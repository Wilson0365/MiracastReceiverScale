package com.weekd.miracastreceiver

class ScaleManager(val tvWidth: Int, val tvHeight: Int) {

    var mode: ScaleMode = ScaleMode.ORIGINAL
        private set

    var offsetY: Float = 0f
        private set

    var offsetX: Float = 0f
        private set

    internal fun forceOffsetY(value: Float) { offsetY = value }
    internal fun forceOffsetX(value: Float) { offsetX = value }

    private val stepNormal: Float get() = tvHeight * 0.05f
    private val stepAccelerated: Float get() = tvHeight * 0.02f

    fun switchMode(newMode: ScaleMode) {
        mode = newMode
        offsetY = 0f
        offsetX = 0f
    }

    fun pan(direction: Int, isAccelerated: Boolean) {
        if (mode == ScaleMode.ORIGINAL) return
        val step = if (isAccelerated) stepAccelerated else stepNormal
        offsetY += direction * step
    }

    fun panHorizontal(direction: Int, isAccelerated: Boolean) {
        if (mode == ScaleMode.ORIGINAL) return
        val step = if (isAccelerated) stepAccelerated else stepNormal
        offsetX += direction * step
    }
}
