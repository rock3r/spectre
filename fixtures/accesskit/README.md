# AccessKit accessibility fixture

This private Rust binary exposes one deterministic native accessibility tree through AccessKit
0.25 and `winit`. It is a test input, not a Spectre implementation or a published artifact.
Future platform readers should launch this same binary and compare their normalized output with the
per-OS files in `golden/`; the small scripts here probe the operating-system API directly and must
not be reused as reader implementations.

The tree contains a labelled button, checked checkbox, valued text field, nested groups, a disabled
button, an off-window node, three elements sharing the `repeated` platform id (including siblings
and different parents), and a click-opened dialog overlay. Linux deliberately expects no text value
until AccessKit's AT-SPI adapter exposes one. Linux role goldens record the stable AT-SPI wire enum
identity as well as a diagnostic name. The probe compares enum IDs, because AT-SPI 2.55 renamed
`PUSH_BUTTON` to `BUTTON` while retaining the same value (43); display names therefore vary between
supported distributions without changing the role identity.

## Local verification

```bash
cargo test --locked --manifest-path fixtures/accesskit/Cargo.toml
cargo build --locked --manifest-path fixtures/accesskit/Cargo.toml
dbus-run-session -- xvfb-run -a fixtures/accesskit/run-linux.sh
```

Linux needs
`xvfb at-spi2-core python3-gi python3-pyatspi gir1.2-atspi-2.0 libxkbcommon-x11-0`.
The runner verifies both the reader-style session enable/restore path and an actual
`accessibility-disabled` refusal while `org.a11y.Status.IsEnabled` is false.

On Windows, build the fixture, start the resulting exe, and run:

```powershell
fixtures/accesskit/readers/windows.ps1 -ProcessId $process.Id -Golden fixtures/accesskit/golden/windows.json
```

GitHub-hosted macOS cannot grant Accessibility permission non-interactively, so macOS is intentionally
release-smoke-only rather than a green CI cell. On a trusted Mac, start the fixture and run:

```bash
swiftc fixtures/accesskit/readers/macos.swift -o /tmp/spectre-accesskit-reader
/tmp/spectre-accesskit-reader "$fixture_pid" fixtures/accesskit/golden/macos.json
```

Grant Accessibility to the terminal or signed wrapper that launches the Swift probe. A permission
failure is a failure, never a skipped or passing cell.
