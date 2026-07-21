package dev.sebastiano.spectre.recording.windows

import java.awt.Rectangle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsGraphicsCaptureArgumentsTest {

    @Test
    fun `recording argv includes mode title owner pid cursor fps and output`() {
        val helper = Path.of("C:/tools/spectre-window-capture.exe")
        val output = Path.of("C:/tmp/out.mp4")

        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Recording,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = "Spectre",
                    ownerPid = 4242,
                    output = output,
                    fps = 60,
                    captureCursor = false,
                )
                .toArgv(helper)

        assertEquals(
            listOf(
                helper.toString(),
                "--mode",
                "recording",
                "--source",
                "window",
                "--title",
                "Spectre",
                "--owner-pid",
                "4242",
                "--fps",
                "60",
                "--cursor",
                "false",
                "--output",
                output.toString(),
            ),
            argv,
        )
    }

    @Test
    fun `screenshot argv uses screenshot mode`() {
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Screenshot,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = "Spectre",
                    ownerPid = 4242,
                    output = Path.of("C:/tmp/out.png"),
                    fps = 30,
                    captureCursor = true,
                )
                .toArgv(Path.of("helper.exe"))

        assertEquals("screenshot", argv[argv.indexOf("--mode") + 1])
    }

    @Test
    fun `region recording argv includes source and rectangle`() {
        val helper = Path.of("C:/tools/spectre-window-capture.exe")
        val output = Path.of("C:/tmp/region.mp4")

        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Recording,
                    source = WindowsGraphicsCaptureSource.Region,
                    region = Rectangle(10, 20, 300, 200),
                    output = output,
                    fps = 24,
                    captureCursor = true,
                )
                .toArgv(helper)

        assertEquals(
            listOf(
                helper.toString(),
                "--mode",
                "recording",
                "--source",
                "region",
                "--x",
                "10",
                "--y",
                "20",
                "--width",
                "300",
                "--height",
                "200",
                "--fps",
                "24",
                "--cursor",
                "true",
                "--output",
                output.toString(),
            ),
            argv,
        )
    }

    @Test
    fun `window argv includes crop flags when crop is set`() {
        val helper = Path.of("helper.exe")
        val output = Path.of("out.mp4")
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Recording,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = "Spectre",
                    ownerPid = 9,
                    crop = Rectangle(8, 40, 640, 480),
                    output = output,
                    fps = 30,
                    captureCursor = true,
                )
                .toArgv(helper)
        assertEquals("8", argv[argv.indexOf("--crop-x") + 1])
        assertEquals("40", argv[argv.indexOf("--crop-y") + 1])
        assertEquals("640", argv[argv.indexOf("--crop-width") + 1])
        assertEquals("480", argv[argv.indexOf("--crop-height") + 1])
    }

    @Test
    fun `arguments reject invalid values`() {
        assertFailsWith<IllegalArgumentException> {
            WindowsGraphicsCaptureArguments(
                mode = WindowsGraphicsCaptureMode.Recording,
                source = WindowsGraphicsCaptureSource.Window,
                title = "",
                ownerPid = 1,
                output = Path.of("out.mp4"),
                fps = 30,
                captureCursor = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsGraphicsCaptureArguments(
                mode = WindowsGraphicsCaptureMode.Recording,
                source = WindowsGraphicsCaptureSource.Window,
                title = "Spectre",
                ownerPid = 0,
                output = Path.of("out.mp4"),
                fps = 30,
                captureCursor = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsGraphicsCaptureArguments(
                mode = WindowsGraphicsCaptureMode.Recording,
                source = WindowsGraphicsCaptureSource.Window,
                title = "Spectre",
                ownerPid = 1,
                output = Path.of("out.mp4"),
                fps = 0,
                captureCursor = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsGraphicsCaptureArguments(
                mode = WindowsGraphicsCaptureMode.Recording,
                source = WindowsGraphicsCaptureSource.Region,
                region = Rectangle(0, 0, 0, 100),
                output = Path.of("out.mp4"),
                fps = 30,
                captureCursor = true,
            )
        }
    }
}
