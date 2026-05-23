# Spectre Windows Graphics Capture helper

This .NET 8 Windows helper owns Spectre's native Windows window capture. It is packaged by
`:recording-windows` as a runtime resource for both x64 and arm64:

```text
native/windows/<arch>/spectre-window-capture.exe
```

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
