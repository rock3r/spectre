# Spectre 0.10.0

Spectre 0.10.0 adds read-only OS accessibility inspection and makes capture, recording, and
failure-evidence scopes explicit. Operations that cannot produce the requested pixels fail rather
than silently substituting another source.

## OS accessibility trees

The CLI and MCP can inspect the accessibility tree exposed to screen readers without attaching to
the target process, including non-JVM applications. For example:

```shell
spectre a11y tree --pid 12345 \
  | jq '.nodes[] | select(.id == "account-name") | {key, parentKey, value}'
```

Native accessibility output uses schema v2 and `source: "os-accessibility"`. Direct results and
atomic `capture.json` files are different shapes. Node keys are snapshot-local, values are
nullable, and bounds spaces vary by provider. A `limit-reached` result can contain a coherent
`partialSnapshot`, but it is not a complete tree. See the [accessibility tree guide](guide/accessibility.md)
and [schema versioning](guide/capture.md#schema-versioning).

Platform gaps remain visible: macOS requires Accessibility permission; Windows cannot inspect a
higher-integrity target and needs its documented runtimes; Linux may temporarily enable the
session AT-SPI bus. GNOME Wayland accessibility bounds can be window-relative, and providers do
not expose every Compose node, value, or state. Spectre does not synthesize missing data.

## Exact capture and recording scopes

- `waitForVisualIdle()` requires native window capture by default. Missing helpers, unavailable
  platform capture, and non-interactive hosted Windows fail with remediation. Set
  `allowScreenRegionFallback = true` only when an occlusion-sensitive desktop crop is acceptable.
- Still and video APIs distinguish window, display, and region scopes. Unsupported window capture
  fails instead of quietly recording a display or cropping the desktop. See [recording](guide/recording.md)
  and [visual idle](guide/synchronization.md#waitforvisualidle).
- The attached CLI's `capture --window` contract is narrower than in-process OS selection: a
  `Frame` or `Dialog` needs a unique, non-blank title, while an untitled `JWindow` is matched by
  owner, position, and size. See [attached capture](guide/capture.md#attached-applications).
- Attached fullscreen stills remain at device scale. A target using an old core without
  `screenshotAtDeviceScale`, or an IPC frame budget too small for the PNG, fails instead of
  returning logical-resolution pixels. Update the target core or raise `--max-frame-bytes` /
  `SPECTRE_MAX_FRAME_BYTES`.

The final measured GNOME implementation uses the requesting PID and exact Xwayland XID to select
the Mutter window, then consumes that association once. It does not select by focus: focus may
remain on an occluding application. GNOME support still requires the Spectre Shell extension,
and other Wayland compositors do not gain equivalent window capture.

Portal grants are reused only while the helper session is live or when the portal supplies a
restorable token. Ubuntu 22.04 / GNOME 42 supplied no stored token, so capture after restart opened
a second picker. Ubuntu 24.04 / GNOME 46 stored a token; restoration after restart was not
verified. See [Linux Wayland consent](guide/recording.md#linux-wayland-consent) and
[Linux Wayland window capture](guide/recording.md#linux-wayland-window-capture).

## JUnit failure evidence

JUnit failure stills and screenshot-gold assertions already existed in 0.9.0. In 0.10.0, requested
failure evidence has an explicit window/video scope and does not silently fall back to a different
source. A capture or recording problem retains the original test failure. JUnit 5 reports absent
evidence as `spectre.failureArtifactMissing` or `spectre.failureVideoMissing`; JUnit 4 prints the
equivalent diagnostic to stderr. Failure video remains off by default, and gold updates remain
opt-in. See [failure artifacts](guide/junit.md#failure-artifacts),
[failure video](guide/junit.md#failure-video), and [screenshot golds](guide/junit.md#screenshot-golds).

## Compatibility

The previous three-argument `waitForVisualIdle` JVM entry point remains available for binary
consumers and uses the fail-closed default. Keep attached CLI and target-core versions aligned;
typed failures replace inexact results rather than changing the requested scope.
