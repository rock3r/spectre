package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.GstCli
import dev.sebastiano.spectre.recording.Recorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [Recorder] for Linux Wayland sessions, backed by `xdg-desktop-portal` (via [ScreenCastPortal])
 * for permission + PipeWire stream selection, and `gst-launch-1.0` for the actual encoding — once
 * the FD-passing piece is in place.
 *
 * **STAGE 2 SHIPS THE ARCHITECTURE; STAGE 3 SHIPS THE ENCODER.** The portal handshake
 * ([ScreenCastPortal] — `CreateSession` → `SelectSources` → `Start`) works end-to-end on GNOME
 * mutter (validated on Hyper-V Ubuntu 22.04, 2026-05-01). What's not yet in place is the
 * JVM-to-gst-launch FD inheritance: pipewiresrc reads portal-granted nodes only via the file
 * descriptor returned by `OpenPipeWireRemote`, and the JDK's `ProcessBuilder` doesn't inherit
 * arbitrary FDs across exec. Without the FD, gst-launch reaches PLAYING state but receives zero
 * frames from the daemon and the output mp4 is 0 bytes — exactly the silent-corruption antipattern
 * that bit us in #76. To avoid shipping that for the second time, [start] throws an
 * [UnsupportedOperationException] with a specific, actionable message AFTER the portal handshake
 * completes, BEFORE doing anything that would produce a junk recording. Callers see "the portal
 * works; the FD plumbing doesn't" rather than "0-byte mp4."
 *
 * Stage 3 replaces the throw with: `OpenPipeWireRemote` to obtain the FD, JNR-POSIX `fcntl(F_SETFD,
 * flags & ~FD_CLOEXEC)` to clear `O_CLOEXEC` on the FD, then spawn `gst-launch-1.0 ... pipewiresrc
 * fd=$N path=$nodeId ...` with the FD inherited. The argv builder ([GstCli.pipewireRegionCapture])
 * is already in place and unit-tested — stage 3 just wires the spawn lifecycle.
 *
 * Module-internal: callers go through [dev.sebastiano.spectre.recording.AutoRecorder] (which routes
 * Wayland sessions here) or construct [WaylandPortalRecorder] directly from within the recording
 * module / its tests.
 */
internal class WaylandPortalRecorder(
    private val portal: ScreenCastPortal = ScreenCastPortal(),
    private val gstLaunchPath: Path = resolveGstLaunchPath(),
    private val sourceTypes: Set<SourceType> = setOf(SourceType.MONITOR),
) : Recorder {

    init {
        // Anticipatory: stage 3 needs gst-launch on PATH. Constructing eagerly so a misconfigured
        // host fails at AutoRecorder() time rather than at the first start() call. The PROBE_PATH
        // sentinel lets construction succeed when gst-launch isn't installed (which the
        // AutoRecorder default does swallow) — start() would then fail with the stage-2 throw,
        // not a "binary not found" error, but that's fine for the partial state.
        require(Files.isExecutable(gstLaunchPath) || gstLaunchPath == PROBE_PATH) {
            "gst-launch-1.0 binary not executable at $gstLaunchPath. Install via " +
                "`sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-good " +
                "gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly` on Debian/Ubuntu."
        }
    }

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val session =
            portal.openSession(
                sourceTypes = sourceTypes,
                cursorMode = if (options.captureCursor) CursorMode.EMBEDDED else CursorMode.HIDDEN,
            )
        try {
            // Translate the requested region into the PipeWire stream's coordinate space.
            // `position` is the stream's top-left in monitor coords; for a single-monitor
            // capture this is usually (0,0) but multi-monitor or virtual-display setups can
            // shift it. The user passes `region` in AWT screen coords; we subtract the stream
            // position so the videocrop pixel insets are correct.
            val streamPosition = session.position
            val streamRelativeRegion =
                Rectangle(
                    region.x - streamPosition.first,
                    region.y - streamPosition.second,
                    region.width,
                    region.height,
                )
            // Build the gst-launch argv we WOULD spawn (once stage 3 lands the FD passing).
            // Calling the builder here exercises the full geometry math that unit tests cover,
            // and the diagnostic below tees the resolved argv into the smoke's log so a future
            // engineer doesn't have to re-derive it.
            val argv =
                GstCli.pipewireRegionCapture(
                    gstLaunchPath = gstLaunchPath,
                    pipewireNodeId = session.nodeId,
                    region = streamRelativeRegion,
                    streamSize = session.size,
                    output = output,
                    options = options,
                )
            System.err.println(
                "[WaylandPortalRecorder] portal handshake OK: node=${session.nodeId}, " +
                    "stream=${session.size}, position=${session.position}, " +
                    "region-relative=$streamRelativeRegion"
            )
            System.err.println("[WaylandPortalRecorder] would-spawn argv: $argv")
            // STAGE 2 THROW — see KDoc on the class. The portal handshake + argv build above
            // are the parts that work; everything past here is staged for stage 3.
            throw UnsupportedOperationException(
                "Wayland recording is partially implemented (#77 stage 2). The xdg-desktop-" +
                    "portal handshake completed: PipeWire stream node ${session.nodeId} was " +
                    "granted (${session.size.first}x${session.size.second}). The encoder spawn " +
                    "is NOT yet wired up: pipewiresrc requires the FD from " +
                    "OpenPipeWireRemote to read portal-granted nodes, and the JVM-to-" +
                    "subprocess FD-inheritance plumbing (JNR-POSIX CLOEXEC manipulation) " +
                    "isn't built yet — tracked at " +
                    "https://github.com/rock3r/spectre/issues/80. Workarounds in the meantime: " +
                    "(a) run under Xvfb (LinuxX11Grab works there), (b) switch the host " +
                    "session to Xorg (WaylandEnable=false in /etc/gdm3/custom.conf)."
            )
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // Anything that escapes after the portal session opened MUST close the session,
            // otherwise the compositor leaves a "Spectre is sharing your screen" indicator
            // hanging and the user has to manually revoke it.
            runCatching { session.close() }
            throw t
        }
    }

    companion object {

        // Sentinel returned by resolveGstLaunchPath() when no binary is found. Constructing a
        // WaylandPortalRecorder against this path lets the caller see the explicit failure
        // message at start() time rather than at construction (matching FfmpegRecorder's
        // PROBE_PATH ergonomics).
        internal val PROBE_PATH: Path = Paths.get("gst-launch-1.0")

        fun resolveGstLaunchPath(): Path = which("gst-launch-1.0") ?: PROBE_PATH

        private fun which(executable: String): Path? {
            val pathEnv = System.getenv("PATH") ?: return null
            val separator = System.getProperty("path.separator") ?: ":"
            for (dir in pathEnv.split(separator)) {
                if (dir.isBlank()) continue
                val candidate = Paths.get(dir, executable)
                if (Files.isExecutable(candidate)) return candidate.toAbsolutePath()
            }
            return null
        }
    }
}
