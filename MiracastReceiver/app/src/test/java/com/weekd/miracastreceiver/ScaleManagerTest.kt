package com.weekd.miracastreceiver

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScaleManagerTest {

    private lateinit var mgr: ScaleManager

    @Before
    fun setup() {
        mgr = ScaleManager(tvWidth = 1920, tvHeight = 1080)
    }

    @Test
    fun `ORIGINAL returns scale 1 and zero translation`() {
        mgr.switchMode(ScaleMode.ORIGINAL)
        val t = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400)
        assertEquals(1f, t.scale, 0.001f)
        assertEquals(0f, t.tx, 0.001f)
        assertEquals(0f, t.ty, 0.001f)
    }

    @Test
    fun `ASPECT_FILL fills width for portrait phone on landscape TV`() {
        // 1080x2400 phone → 1920x1080 TV
        // max(1920/1080, 1080/2400) = max(1.777, 0.45) = 1.777
        mgr.switchMode(ScaleMode.ASPECT_FILL)
        val t = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400)
        assertEquals(1920f / 1080f, t.scale, 0.01f)
    }

    @Test
    fun `ASPECT_FILL does not downscale landscape phone`() {
        // 1920x1080 phone → 1920x1080 TV: scale should be exactly 1
        mgr.switchMode(ScaleMode.ASPECT_FILL)
        val t = mgr.buildTransform(srcWidth = 1920, srcHeight = 1080)
        assertEquals(1f, t.scale, 0.001f)
    }

    @Test
    fun `SCALE_125 applies 1_25 scale centered`() {
        mgr.switchMode(ScaleMode.SCALE_125)
        val t = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400)
        assertEquals(1.25f, t.scale, 0.001f)
        // tx: (1920 - 1080*1.25) / 2 = (1920 - 1350) / 2 = 285
        assertEquals((1920f - 1080f * 1.25f) / 2f, t.tx, 0.1f)
    }

    @Test
    fun `SCALE_200 applies 2x scale centered`() {
        mgr.switchMode(ScaleMode.SCALE_200)
        val t = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400)
        assertEquals(2f, t.scale, 0.001f)
    }

    @Test
    fun `switchMode resets offsetY to 0`() {
        mgr.switchMode(ScaleMode.SCALE_200)
        mgr.pan(direction = 1, isAccelerated = false)
        mgr.switchMode(ScaleMode.SCALE_125)
        assertEquals(0f, mgr.offsetY, 0.001f)
    }

    @Test
    fun `pan moves ty downward`() {
        mgr.switchMode(ScaleMode.SCALE_200)
        val before = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400).ty
        mgr.pan(direction = 1, isAccelerated = false)
        val after = mgr.buildTransform(srcWidth = 1080, srcHeight = 2400).ty
        assertTrue("pan down should decrease ty (move content up)", after < before)
    }

    @Test
    fun `pan clamps so content edge never goes past screen edge`() {
        // 1080x2400 * 2.0 scale → scaledH = 4800; maxOffsetY = (4800 - 1080) / 2 = 1860
        mgr.switchMode(ScaleMode.SCALE_200)
        repeat(1000) { mgr.pan(direction = 1, isAccelerated = false) }
        mgr.clampOffset(srcWidth = 1080, srcHeight = 2400)
        val maxOffset = (2400f * 2f - 1080f) / 2f
        assertTrue(mgr.offsetY <= maxOffset + 0.1f)
    }

    @Test
    fun `pan does nothing in ORIGINAL mode`() {
        mgr.switchMode(ScaleMode.ORIGINAL)
        mgr.pan(direction = 1, isAccelerated = false)
        assertEquals(0f, mgr.offsetY, 0.001f)
    }

    @Test
    fun `buildTransform with zero srcSize returns identity`() {
        mgr.switchMode(ScaleMode.SCALE_150)
        val t = mgr.buildTransform(srcWidth = 0, srcHeight = 0)
        assertEquals(1f, t.scale, 0.001f)
        assertEquals(0f, t.tx, 0.001f)
        assertEquals(0f, t.ty, 0.001f)
    }

    @Test
    fun `accelerated pan step is smaller than normal step`() {
        // accelerated = 2% per frame, normal = 5% per frame
        mgr.switchMode(ScaleMode.SCALE_200)
        mgr.pan(direction = 1, isAccelerated = false)
        val normalStep = mgr.offsetY

        mgr.switchMode(ScaleMode.SCALE_200)  // reset
        mgr.pan(direction = 1, isAccelerated = true)
        val accelStep = mgr.offsetY

        assertTrue("accelerated step should be smaller", accelStep < normalStep)
    }
}
