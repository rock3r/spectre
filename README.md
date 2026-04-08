# Spectre

Bootstrap repository for a Compose Desktop automator focused on real-time desktop automation,
recording, and profiling workflows.

This repo is intentionally at the scaffold stage. The project structure is ready for the spike,
but the automation features themselves are not implemented yet.

## Modules

- `core`: desktop automation primitives and shared model
- `server`: future cross-JVM transport layer
- `recording`: future recording and capture integration
- `testing`: future JUnit-facing helpers
- `sample-desktop`: small desktop app kept around for manual verification during the spike

## Run the sample app

```shell
./gradlew :sample-desktop:run
```

## Reference plan

- [Bootstrap notes](./docs/bootstrap-plan.md)
- [Spike gist](https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8)
