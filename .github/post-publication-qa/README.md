# Scratch hosted post-publication QA

This is temporary, scratch-branch-only QA for already public artifacts. It never builds release
source and has read-only repository permission. `control.env` remains deliberately held at
`0.9.0`; `0.10.0` must not be selected until that exact version is specifically requested and its
public, peeled tag SHA is known.

A control promotion uses the `Probe published Spectre` commit prefix. Corrections to these QA
files for the same public tag use `Correct post-publication QA`; the workflow rejects product-file
changes in that case. There is intentionally no manual-dispatch entry point.

## What is proved

* GitHub release identity, peeled tag SHA, asset API SHA-256, and downloaded SHA-256 must agree.
* Both Linux archives run only on the exactly matching `platform.machine()`. Extraction preserves
  stored Unix modes and symlinks; the CLI must be a real executable file and print exactly
  `spectre <version>`, and both its top-level and `record` help must succeed with nonempty output.
  JAR inspection reads only candidate helper headers before streaming the one
  matching ELF out. The historical 0.9 helper receives a finite genuine recording-protocol probe,
  and makes no accessibility claim.
* Every one of the eleven Spectre GAVs is downloaded from a fresh Maven Central location as its
  exact JAR, POM, and Gradle module, nontransitively. Separately, a clean Maven/JDK 21 project with
  a pinned compiler plugin consumes the genuine `spectre-recording` graph and calls the stable
  public Java ABI `new RecordingOptions().getFrameRate()/getCaptureCursor()`. `HostPlatform` is
  intentionally not used because it is Kotlin `internal` in the authentic 0.9 source. The public
  no-argument options API is checked from the selected public artifact, so the same check applies
  after a future 0.10 promotion. A Central-only graph failure remains an explicit failing log;
  Google is never silently added and an unavailable Google-only Compose native dependency is not
  mislabeled as a missing Spectre coordinate.
* Only when the control is `0.10.0`, on both native x64 and arm64 runners the **shipped**
  `spectre a11y tree --pid` targets a separate,
  visible GTK window inside a private D-Bus/AT-SPI/Xvfb session with accessibility enabled. The
  fixture has unique window and button markers. Acceptance requires a real schema version 2
  `os-accessibility` snapshot, authentic target PID and window `title`/`platformId`, and the unique
  button's `role`, `name`, `key`, and valid `parentKey` graph. Each run uses its own private daemon
  socket and stops the daemon in cleanup. The invalid historical `{pid:1}` shape probe is not used.

Uploads run even after failures but contain only compact identity, command/status/error text, and
fixture JSON. Public archives, unpacked runtimes, temporary Maven repositories, and private bus
state stay under the runner temporary directory and are never uploaded.
