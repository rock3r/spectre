# Real cache-less AT-SPI depth acceptance

This scratch QA owns a private session bus, Xvfb display, real `at-spi-bus-launcher`
and registry, and a headed GTK process that registers its custom provider through
`org.a11y.atspi.Socket.Embed`. The provider deliberately exports no Cache interface.
It is not a wire mock and the product reader is never replaced or shimmed.

Every run first builds and executes public `fe9b574d491608522a1caf31e4aab28ef2a22a94`.
Its durable receipt must show the known late-sibling prefetch. A coordinator can then
set either `CORRECTED_SHA` or `CORRECTED_PATCH`, plus the exact expected reader tree,
in `control.env`. Candidate cells demand the two-node depth-1 partial snapshot, run a
normal full-depth positive control, and repeat depth 1 against explicit-error and
bounded-slow late siblings.

The uploaded directory contains native stdout JSON, stderr, raw Cache failure JSON,
provider JSONL (sender unique name and live PID lookup), complete filtered D-Bus wire
logs, extracted reader Cache calls/errors, and final receipts. All helper output goes
directly to files; subprocesses have finite deadlines.
