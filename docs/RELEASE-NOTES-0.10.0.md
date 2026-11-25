# Spectre 0.10.0 capture exactness

Spectre 0.10.0 makes capture mechanism, geometry, and resolution observable instead of silently
substituting a similar result.

- `waitForVisualIdle()` requires native window capture by default. Missing GStreamer, a missing
  platform runtime helper, disabled platform capture, and non-interactive hosted Windows fail with
  remediation. Call `waitForVisualIdle(allowScreenRegionFallback = true)` only when an
  occlusion-sensitive desktop crop is acceptable.
- Attached fullscreen stills remain device-scale. An old target core or an IPC budget too small for
  that PNG now fails instead of returning logical-resolution pixels.
- Window crops and Linux portal stream crops reject geometry outside their source (apart from a
  one-pixel HiDPI rounding tolerance) instead of clipping it.

These are intentional compatibility changes: CI jobs which previously relied on visual-idle's
desktop fallback must opt in or install the platform capture prerequisites.
