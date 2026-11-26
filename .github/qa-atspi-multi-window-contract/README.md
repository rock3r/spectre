# Multi-window native AT-SPI contract QA

This scratch-only lane compiles the immutable public `#716` baseline and optionally a precisely
pinned reviewed candidate. It does not modify product sources. A real GTK3 process maps two X11
windows while its cacheless adapter registers one application owner with the private AT-SPI bus.

Baseline assertions are observations: all three independent providers run even when the old binary
selects only its first PID-owned window or returns a populated partial snapshot for an ordinary
structural error. Candidate assertions are acceptance requirements and make the job fail.

To test a review, set exactly one selector in `candidate.env`. `CANDIDATE_SHA` must be a subsequent
exact commit. Alternatively, `CANDIDATE_PATCH` names a cumulative patch in this directory which is
applied with `git apply --index` to the baseline. Record both the resulting full repository tree and
the `recording/native/linux` tree. The workflow verifies those objects before any build.
