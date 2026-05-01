package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Argv-shape tests for [GstCli.pipewireRegionCapture].
 *
 * The Wayland recording path can't be exercised in CI without an actual Wayland session +
 * compositor + portal grant, so the pure argv builder is the only deterministic surface we can
 * unit-test. The end-to-end happy path is covered by the manual smoke `runFfmpegPipeWireSmoke`
 * (validated on the dev VM in the PR description).
 */
class GstCliTest {

    private val gstLaunch: Path = Path.of("/usr/bin/gst-launch-1.0")
    private val output: Path = Path.of("/tmp/spectre/out.mp4")
    private val region = Rectangle(100, 200, 640, 480)
    private val streamSize = 1920 to 1080
    private val pipewireNodeId = 42

    @Test
    fun `argv starts with the gst-launch path`() {
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        assertEquals(gstLaunch.toString(), argv.first())
    }

    @Test
    fun `argv emits -e for clean shutdown by default`() {
        // -e turns SIGTERM into End-Of-Stream so the mux can finalise the file. Production
        // path always wants this; tests can disable for argv-only assertions.
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        assertTrue("-e" in argv, "Should pass -e for EOS-on-SIGTERM: $argv")
    }

    @Test
    fun `argv does NOT pass -q so gst-launch can surface plugin and stream errors`() {
        // gst-launch's `-q` suppresses progress AND filters out diagnostic warnings; we'd
        // rather see them — they're the breadcrumb trail when pipewiresrc can't connect to
        // the portal-granted node, when an encoder plugin is missing, etc. The captured
        // stderr is what the recorder uses to surface "0-byte recording" failures into a
        // useful error message.
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        assertTrue("-q" !in argv, "Should NOT pass -q so gst-launch is diagnostic: $argv")
    }

    @Test
    fun `argv omits -e when eosOnExit is false`() {
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
                eosOnExit = false,
            )
        assertTrue("-e" !in argv, "Should NOT pass -e when eosOnExit=false: $argv")
    }

    @Test
    fun `argv selects the requested PipeWire stream node id`() {
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId = 1234,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        // pipewiresrc takes the node id via `path=` (a property assignment in gst-launch
        // syntax); the value is the integer node id rendered as a decimal string.
        assertContainsSequence(argv, listOf("pipewiresrc", "do-timestamp=true", "path=1234"))
    }

    @Test
    fun `argv translates region rectangle to videocrop pixel insets`() {
        // For a 640x480 region at (100, 200) inside a 1920x1080 stream, the insets should be:
        //   top=200  bottom=1080-(200+480)=400
        //   left=100 right=1920-(100+640)=1180
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(100, 200, 640, 480),
                1920 to 1080,
                output,
                RecordingOptions(),
            )
        assertContainsSequence(
            argv,
            listOf("videocrop", "top=200", "bottom=400", "left=100", "right=1180"),
        )
    }

    @Test
    fun `argv pins framerate via videorate before the encoder`() {
        // videorate enforces a fixed CFR before x264enc / mp4mux see the frames; without it,
        // PipeWire's natural variable-framerate output trips the muxer's timestamp checks.
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(frameRate = 60),
            )
        assertContainsSequence(argv, listOf("videorate", "!", "video/x-raw,framerate=60/1"))
    }

    @Test
    fun `argv configures x264 with low-latency tuning for capture-time perf`() {
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        assertContainsSequence(
            argv,
            listOf("x264enc", "tune=zerolatency", "speed-preset=ultrafast"),
        )
    }

    @Test
    fun `argv writes faststart MP4 to filesink with the requested output path`() {
        val argv =
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        assertContainsSequence(
            argv,
            listOf("mp4mux", "faststart=true", "!", "filesink", "location=${output}"),
        )
    }

    @Test
    fun `argv rejects non-positive region dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(0, 0, 0, 100),
                streamSize,
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(0, 0, 100, 0),
                streamSize,
                output,
                RecordingOptions(),
            )
        }
    }

    @Test
    fun `argv rejects negative region origin`() {
        // videocrop's pixel-inset form silently produces nonsense if right/bottom go negative
        // (which a negative origin would cause via streamWidth - (x + width)). Reject up-front.
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(-1, 0, 100, 100),
                streamSize,
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(0, -10, 100, 100),
                streamSize,
                output,
                RecordingOptions(),
            )
        }
    }

    @Test
    fun `argv rejects regions that exceed the stream bounds`() {
        // A region that pokes outside the stream means the caller passed mismatched coords —
        // either AWT-screen coords without translating to stream-relative, or a bug in the
        // portal's stream-size reporting. Either way we catch it here instead of producing
        // a videocrop with negative right/bottom values that gst-launch would later reject.
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(0, 0, 2000, 100),
                streamSize, // 1920x1080
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId,
                Rectangle(0, 0, 100, 2000),
                streamSize,
                output,
                RecordingOptions(),
            )
        }
    }

    @Test
    fun `argv rejects negative pipewire node id`() {
        assertFailsWith<IllegalArgumentException> {
            GstCli.pipewireRegionCapture(
                gstLaunch,
                pipewireNodeId = -1,
                region,
                streamSize,
                output,
                RecordingOptions(),
            )
        }
    }
}

private fun assertContainsSequence(argv: List<String>, expected: List<String>) {
    val matched = argv.windowed(expected.size).any { it == expected }
    check(matched) { "Expected $expected to appear contiguously in $argv" }
}
