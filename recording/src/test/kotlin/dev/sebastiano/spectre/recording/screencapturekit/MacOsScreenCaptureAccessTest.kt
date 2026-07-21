package dev.sebastiano.spectre.recording.screencapturekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsScreenCaptureAccessTest {

    @Test
    fun `preflight parses granted JSON from helper`() {
        val json =
            """{"granted":true,"api":"CGPreflightScreenCaptureAccess","binary":"/tmp/h","settings_path":"Settings","deep_link":"x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture","guidance":"ok"}"""
        val factory = MacOsScreenCaptureAccess.ProcessFactory {
            FakeProcess(stdout = json + "\n", exitCode = 0)
        }
        val extractor = stubExtractor()
        val result =
            MacOsScreenCaptureAccess.preflight(
                helperExtractor = extractor,
                processFactory = factory,
            )
        assertTrue(result.granted)
        assertEquals("/tmp/h", result.binaryPath)
        assertTrue(result.relayMessage().contains("granted", ignoreCase = true))
    }

    @Test
    fun `preflight denied throws structured exception via requireGranted`() {
        val json =
            """{"granted":false,"api":"CGPreflightScreenCaptureAccess","binary":"/tmp/spectre-screencapture","settings_path":"System Settings → Privacy & Security → Screen & System Audio Recording","deep_link":"x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture","guidance":"denied for agents"}"""
        val factory = MacOsScreenCaptureAccess.ProcessFactory {
            FakeProcess(stdout = json + "\n", exitCode = 6)
        }
        val extractor = stubExtractor()
        val denied =
            assertFailsWith<ScreenCaptureAccessDeniedException> {
                MacOsScreenCaptureAccess.requireGranted(
                    helperExtractor = extractor,
                    processFactory = factory,
                )
            }
        assertFalse(denied.result.granted)
        assertTrue(denied.message!!.contains("Deep link"))
        assertTrue(denied.message!!.contains("Privacy_ScreenCapture"))
        assertTrue(denied.message!!.contains("/tmp/spectre-screencapture"))
    }

    @Test
    fun `request mode invokes helper with --mode request`() {
        var argv: List<String> = emptyList()
        val json =
            """{"granted":true,"api":"CGPreflightScreenCaptureAccess","binary":"/h","settings_path":"s","deep_link":"d","guidance":"g"}"""
        val factory = MacOsScreenCaptureAccess.ProcessFactory { args ->
            argv = args
            FakeProcess(stdout = json + "\n", exitCode = 0)
        }
        MacOsScreenCaptureAccess.request(
            helperExtractor = stubExtractor(),
            processFactory = factory,
        )
        assertEquals("request", argv[argv.indexOf("--mode") + 1])
    }

    private fun stubExtractor(): HelperBinaryExtractor {
        val dir = Files.createTempDirectory("spectre-preflight-test")
        val helper = dir.resolve("spectre-screencapture")
        Files.writeString(helper, "#!/bin/sh\n")
        helper.toFile().setExecutable(true)
        return HelperBinaryExtractor(
            envLookup = { key ->
                if (key == "SPECTRE_SCREENCAPTURE_HELPER") helper.toString() else null
            },
            sysPropLookup = { null },
            resourceLocator = { null },
            targetDirProvider = { dir },
        )
    }

    private class FakeProcess(stdout: String, private val exitCode: Int) : Process() {
        private val input = ByteArrayInputStream(stdout.toByteArray())
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = output

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = exitCode

        override fun destroy() = Unit

        override fun destroyForcibly(): Process = this
    }
}
