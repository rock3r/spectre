# Spectre Windows Graphics Capture helper

This .NET 8 Windows helper owns Spectre's native Windows window capture. It is packaged by
`:recording-windows` as a **multi-file** runtime resource directory for both x64 and arm64:

```text
native/windows/<arch>/spectre-window-capture.exe
native/windows/<arch>/SpectreWindowCapture.dll
native/windows/<arch>/SpectreWindowCapture.deps.json
native/windows/<arch>/SpectreWindowCapture.runtimeconfig.json
native/windows/<arch>/… WASDK / Win2D / WinRT companions …
```

The runtime extractor copies every regular file under each arch directory before launch.
Packaging verification (`:recording-windows:verifyRecordingWindowsHelper` and
`:verifyMavenLocalPublication`) enforces the multi-file contract — an orphan
`spectre-window-capture.exe` without companions fails the gate. See
`WindowsGraphicsCaptureHelperPackagingContract` in `buildSrc`.

The helper is framework-dependent. Runtime machines need:

- Windows 10 version 1903 or newer
- .NET 8 Desktop Runtime
- Windows App Runtime 1.8

Contributors and CI that build the helper from source need the .NET 8 SDK.

## CLI contract

```text
spectre-window-capture.exe \
  --mode screenshot|recording \
  --source window|region \
  --title <exact window title> \
  --owner-pid <pid> \
  --x <virtual-screen-x> \
  --y <virtual-screen-y> \
  --width <pixels> \
  --height <pixels> \
  --fps <frames-per-second> \
  --cursor true|false \
  --output <png-or-mp4>
```

Window capture requires `--title` and `--owner-pid`. Region capture requires `--x`, `--y`,
`--width`, and `--height`; the rectangle must be fully contained by a single monitor. Fullscreen
recording is represented as a region equal to the monitor bounds.

On Windows the JVM does not put that flag list on the process command line. It writes one token
per line to a temporary UTF-8 args file and launches `spectre-window-capture.exe @<args-file>`.
Paths that contain spaces (the helper lives under `%LOCALAPPDATA%`, and the recording path is
often under a user temp directory) stay one argument each. `NormalizeIncomingArgs` expands
`@<args-file>` and, if the host leaked the program path as the first user token, skips it.
Direct flag argv still parses. A missing args file is exit 2 (`Arguments file not found`).

`:recording:runWindowsGraphicsCaptureRegionSmoke` needs a Windows desktop with Windows Graphics
Capture. It cannot run on a Linux host.

Screenshot mode writes a PNG and exits. Screenshot mode currently supports `--source window`.

Recording mode writes `READY` to stdout after the Windows Graphics Capture and MP4 encoder
pipeline is prepared. The parent JVM stops the recording by writing `q` to stdin. The output
video dimensions are fixed from the target window or region size at capture start.

Build locally with:

```powershell
./gradlew :recording:assembleWindowsScreenshotHelper
```

The task name still contains `ScreenshotHelper` for compatibility with the existing helper staging
pipeline, but the staged executable handles both screenshots and recordings.
