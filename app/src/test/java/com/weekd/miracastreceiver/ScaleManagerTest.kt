package com.weekd.miracastreceiver

import org.junit.Assert.*
import org.junit.Test

class ScaleManagerTest {
    private val manager = ScaleManager(1920, 1080)
    @Test fun originalModeDoesNotPan() {
        manager.pan(1, false)
        manager.panHorizontal(1)
        assertEquals(0f, manager.offsetY, 0f)
        assertEquals(0f, manager.offsetX, 0f)
    }
    @Test fun switchingModeResetsBothOffsets() {
        manager.switchMode(ScaleMode.SCALE_200)
        manager.pan(1, false)
        manager.panHorizontal(-1)
        manager.switchMode(ScaleMode.SCALE_125)
        assertEquals(ScaleMode.SCALE_125, manager.mode)
        assertEquals(0f, manager.offsetY, 0f)
        assertEquals(0f, manager.offsetX, 0f)
    }
    @Test fun verticalPanUsesScreenHeightAndDirection() {
        manager.switchMode(ScaleMode.SCALE_200)
        manager.pan(1, false)
        assertEquals(54f, manager.offsetY, 0.001f)
        manager.pan(-1, false)
        assertEquals(0f, manager.offsetY, 0.001f)
        manager.pan(1, true)
        assertEquals(21.6f, manager.offsetY, 0.001f)
    }
    @Test fun horizontalPanUsesUniformScreenWidthStep() {
        manager.switchMode(ScaleMode.ASPECT_FILL)
        manager.panHorizontal(1)
        assertEquals(7.68f, manager.offsetX, 0.001f)
        manager.panHorizontal(-1)
        assertEquals(0f, manager.offsetX, 0.001f)
    }
}
