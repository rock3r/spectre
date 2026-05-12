# `stub-mac-helper` — Mach-O fat-binary fixture for publication-shape tests

A minimal but **valid** Mach-O fat binary covering `arm64` + `x86_64`. Used by
`:recording:stageStubMacHelper` when the build is invoked with
`-PstubMacHelperForTesting`, so we can verify the publication shape (every
expected file shows up in the recording jar at the right path) on a host that
cannot build a real `SpectreScreenCapture` binary — typically a contributor's
Linux box, or this repo's `:verifyMavenLocalPublication` smoke test on the
release pipeline's Linux runner.

**Never substitute this for a real helper in a release.** The slice payloads
are all-zero — `file(1)` recognises the wrapper, but the binary doesn't *do*
anything. Real releases must stage the notarised universal helper produced
by `:recording:assembleScreenCaptureKitHelperUniversal` (or the equivalent
artefact downloaded from the release CI's macOS job).

Layout:

- Header: `0xCAFEBABE` magic, `nfat_arch=2`.
- `fat_arch[0]`: `cputype=0x0100000C` (arm64), `subtype=0`, `offset=4096`, `size=16`, `align=12`.
- `fat_arch[1]`: `cputype=0x01000007` (x86_64), `subtype=3`, `offset=8192`, `size=16`, `align=12`.
- Payload bytes are zero between offsets — the wrapper is the entirety of
  what we exercise.

To regenerate (Python 3, no external deps):

```python
import struct
buf = bytearray()
buf += struct.pack(">II", 0xCAFEBABE, 2)
buf += struct.pack(">IIIII", 0x0100000C, 0, 4096, 16, 12)
buf += struct.pack(">IIIII", 0x01000007, 3, 8192, 16, 12)
buf += b"\x00" * (4096 - len(buf))
buf += b"\x00" * 16
buf += b"\x00" * (8192 - len(buf))
buf += b"\x00" * 16
open("stub-mac-helper", "wb").write(bytes(buf))
```
