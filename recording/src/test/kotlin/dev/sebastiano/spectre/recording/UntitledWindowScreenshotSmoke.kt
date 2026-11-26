@file:JvmName("UntitledWindowScreenshotSmoke")

package dev.sebastiano.spectre.recording

import java.awt.Color
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Live acceptance for owner-bound native selection of real untitled AWT windows. */
fun main() {
    check(HostPlatform.isWindows() || (HostPlatform.isLinux() && !HostPlatform.isWayland())) {
        "This smoke requires Windows or Linux X11"
    }
    val target = coloredFrame(Color(18, 196, 91), Rectangle(80, 90, 320, 220))
    val decoy = coloredDialog(target, Color(211, 43, 167), Rectangle(480, 90, 260, 180))
    try {
        val targetTitle = target.title
        val decoyTitle = decoy.title
        val image = NativeWindowCaptureBridge.captureWindow(target)
        check(image.containsColorNear(target.background)) {
            "Selected screenshot did not contain the target color ${target.background}: " +
                "${image.width}x${image.height}"
        }
        check(target.title == targetTitle && decoy.title == decoyTitle) {
            "Native selection changed an untitled window title"
        }

        decoy.bounds = target.bounds
        val failure = runCatching {
            NativeWindowCaptureBridge.captureWindow(target)
        }
            .exceptionOrNull()
        checkNotNull(failure) { "Two owner windows with the same frame must be ambiguous" }
        check(target.title == targetTitle && decoy.title == decoyTitle) {
            "Ambiguity detection changed an untitled window title"
        }
        println(
            "Untitled native screenshot selected ${image.width}x${image.height} colored content; " +
                "same-frame ambiguity failed closed; titles remained stable"
        )
    } finally {
        decoy.dispose()
        target.dispose()
    }
}

private fun coloredFrame(color: Color, bounds: Rectangle): Frame =
    Frame().apply {
        background = color
        this.bounds = bounds
        isVisible = true
        toFront()
    }

private fun coloredDialog(owner: Frame, color: Color, bounds: Rectangle): Dialog =
    Dialog(owner).apply {
        background = color
        this.bounds = bounds
        isVisible = true
    }

private fun BufferedImage.containsColorNear(expected: Color): Boolean {
    val stepX = (width / 20).coerceAtLeast(1)
    val stepY = (height / 20).coerceAtLeast(1)
    for (y in 0 until height step stepY) {
        for (x in 0 until width step stepX) {
            val actual = Color(getRGB(x, y), true)
            if (
                actual.alpha > 200 &&
                    kotlin.math.abs(actual.red - expected.red) <= 8 &&
                    kotlin.math.abs(actual.green - expected.green) <= 8 &&
                    kotlin.math.abs(actual.blue - expected.blue) <= 8
            ) {
                return true
            }
        }
    }
    return false
}
