# Bootstrap Plan

This repository is prepared to implement the Compose Desktop automator spike without starting the
feature work yet.

## Source of truth

- External spike plan: <https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8>

## Module map

- `core`: shared desktop automation primitives
- `server`: opt-in transport layer for cross-JVM access
- `recording`: screen capture / recording integration
- `testing`: JUnit-facing helpers and fixtures
- `sample-desktop`: small manual-test app for later spike work

## Intentional non-goals of this bootstrap

- no automation implementation yet
- no HTTP endpoints yet
- no Robot integration yet
- no recording implementation yet
- no popup / semantics spike code yet

