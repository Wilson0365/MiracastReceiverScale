package com.weekd.miracastreceiver

import org.junit.Assert.*
import org.junit.Test

class ImageTransformTest {
    @Test fun imageZoomUsesTheSameSelectedPresetAsVideo() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.SCALE_125)
        val transform = calculateImageTransform(1920f, 1080f, 1920f, 1080f, 0, manager)!!
        assertEquals(1.25f, transform.scale, 0.001f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun repeatedZoomDoesNotAccumulate() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.SCALE_125)
        repeat(10) {
            assertEquals(1.25f, calculateImageTransform(1920f, 1080f, 1920f, 1080f, 0, manager)!!.scale, 0.001f)
        }
    }

    @Test fun panningCannotMoveTheZoomedImageOutsideTheViewport() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.SCALE_125)
        repeat(1000) { manager.pan(1, false); manager.panHorizontal(-1) }
        val transform = calculateImageTransform(1920f, 1080f, 1920f, 1080f, 0, manager)!!
        assertEquals(-240f, transform.offsetX, 0.001f)
        assertEquals(135f, transform.offsetY, 0.001f)
        assertEquals(transform.offsetX, manager.offsetX, 0f)
        assertEquals(transform.offsetY, manager.offsetY, 0f)
    }

    @Test fun originalResetsZoomAndPan() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.SCALE_200)
        manager.pan(1, false)
        manager.switchMode(ScaleMode.ORIGINAL)
        val transform = calculateImageTransform(1920f, 1080f, 1920f, 1080f, 0, manager)!!
        assertEquals(1f, transform.scale, 0f)
        assertEquals(0f, transform.offsetX, 0f)
        assertEquals(0f, transform.offsetY, 0f)
    }

    @Test fun portraitImageFitsAndFillsUsingItsActualDimensions() {
        val manager = ScaleManager(1920, 1080)
        assertEquals(0.5625f, calculateImageTransform(1080f, 1920f, 1920f, 1080f, 0, manager)!!.scale, 0.001f)
        manager.switchMode(ScaleMode.ASPECT_FILL)
        assertEquals(1.777778f, calculateImageTransform(1080f, 1920f, 1920f, 1080f, 0, manager)!!.scale, 0.001f)
    }

    @Test fun rotationAndZoomComposeWithoutStretching() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.SCALE_125)
        assertEquals(1.25f, calculateImageTransform(1080f, 1920f, 1920f, 1080f, 90, manager)!!.scale, 0.001f)
        assertEquals(1.25f, calculateImageTransform(1080f, 1920f, 1920f, 1080f, 270, manager)!!.scale, 0.001f)
    }

    @Test fun largerPresetActuallyMagnifiesImageFourTimes() {
        val manager = ScaleManager(1920, 1080)
        manager.switchMode(ScaleMode.values().first { it.factor == 4f })
        assertEquals(4f, calculateImageTransform(1920f, 1080f, 1920f, 1080f, 0, manager)!!.scale, 0f)
    }

    @Test fun layoutNotReadyDoesNotProduceInvalidMatrixValues() {
        assertNull(calculateImageTransform(1920f, 1080f, 0f, 0f, 0, null))
        assertNull(calculateImageTransform(0f, 1080f, 1920f, 1080f, 0, null))
    }
}
