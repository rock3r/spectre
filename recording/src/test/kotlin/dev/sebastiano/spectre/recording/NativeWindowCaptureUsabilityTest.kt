package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeWindowCaptureUsabilityTest {

    @Test
    fun `Linux requires gst-launch before native stills are treated as usable`() {
        assertFalse(
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { false },
            )
        )
        assertTrue(
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { true },
            )
        )
    }

    @Test
    fun `non-Linux hosts stay usable without gst-launch`() {
        assertTrue(
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { false },
                gstLaunchAvailable = { false },
            )
        )
    }
}
