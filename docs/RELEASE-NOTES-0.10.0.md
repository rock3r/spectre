# Spectre 0.10.0 capture exactness

Spectre 0.10.0 no longer silently substitutes a similar capture result.

- `waitForVisualIdle()` requires native window capture by default. Missing GStreamer, a missing
  platform runtime helper, disabled platform capture, and non-interactive hosted Windows fail with
  remediation. Set `allowScreenRegionFallback = true` only when an occlusion-sensitive desktop
  crop is acceptable.
- Attached fullscreen stills remain device-scale. An old target core or an IPC budget too small
  for that PNG now fails instead of returning logical-resolution pixels.

The old three-argument `waitForVisualIdle` JVM entry point remains available for binary consumers
and uses the new fail-closed default.
