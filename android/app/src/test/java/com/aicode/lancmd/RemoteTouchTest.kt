package com.aicode.lancmd

import org.junit.Assert.*
import org.junit.Test

class RemoteTouchTest {
    @Test fun portraitLetterboxingUsesOnlyVisibleImage() {
        assertNull(RemoteTouch.point(200f, 100f, 400, 800, 1600, 900))
        val center = RemoteTouch.point(200f, 400f, 400, 800, 1600, 900)!!
        assertEquals(0.5f, center.x, 0.0001f); assertEquals(0.5f, center.y, 0.0001f)
        val topLeft = RemoteTouch.point(0f, 287.5f, 400, 800, 1600, 900)!!
        assertEquals(0f, topLeft.x, 0.0001f); assertEquals(0f, topLeft.y, 0.0001f)
    }
    @Test fun landscapeAndMultiMonitorAspectPreserveCoordinates() {
        val point = RemoteTouch.point(600f, 200f, 800, 400, 3840, 1080)!!
        assertEquals(0.75f, point.x, 0.0001f); assertEquals(0.5f, point.y, 0.0001f)
        assertNull(RemoteTouch.point(5f, 200f, 800, 400, 800, 1200))
    }
    @Test fun dragPastFrameClampsReleaseAndInvalidSizesAreIgnored() {
        val point = RemoteTouch.point(-20f, 900f, 400, 800, 1600, 900, clamp = true)!!
        assertEquals(0f, point.x, 0f); assertEquals(1f, point.y, 0f)
        assertNull(RemoteTouch.point(1f, 1f, 0, 800, 100, 100))
        assertNull(RemoteTouch.point(Float.NaN, 1f, 400, 800, 100, 100))
    }
}
