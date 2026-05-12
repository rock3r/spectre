# Publishing

Spectre's library modules (`:core`, `:server`, `:recording`, `:testing`) publish
to Sonatype Central via the [`com.vanniktech.maven.publish`][vanniktech] plugin.
The sample modules (`:sample-desktop`, `:sample-intellij-plugin`) never apply
the plugin — they're deliverables, not libraries.

[vanniktech]: https://github.com/vanniktech/gradle-maven-publish-plugin

## Tag-driven release flow

The pipeline is owned by [`.github/workflows/release.yml`][release-yml] and
triggers on tags matching `v*` (e.g. `v0.2.0`). The leading `v` is stripped to
form the published version, so `v0.2.0` publishes coordinates
`dev.sebastiano.spectre:spectre-core:0.2.0` (and equivalent for the other three
modules).

[release-yml]: https://github.com/rock3r/spectre/blob/main/.github/workflows/release.yml

Three jobs run on tag push:

1. **`mac-helper`** (macOS runner) — builds the arm64+x86_64 universal
   `SpectreScreenCapture` Swift helper, codesigns it with the Developer ID, runs
   `notarytool submit --wait`, and uploads the notarised binary as a GitHub
   Actions artefact.
2. **`linux-helpers`** (Linux runner) — cross-builds the
   `spectre-wayland-helper` Rust binary for `x86_64` and `aarch64` (same
   toolchain dance documented in [`ci.yml`][ci-yml]: dpkg multi-arch +
   per-arch libdbus sysroot + cross-linker). Uploads the per-arch binaries as a
   GitHub Actions artefact.
3. **`publish`** (Linux runner, depends on both) — downloads the helper
   artefacts, runs `:verifyMavenLocalPublication` to assert the publication
   shape, then runs `publishToMavenCentral` against the
   [Sonatype Central Portal][central-portal]. Finally creates a draft GitHub
   release with the cross-platform recording jar attached.

[ci-yml]: https://github.com/rock3r/spectre/blob/main/.github/workflows/ci.yml
[central-portal]: https://central.sonatype.com/

Both the Central Portal deployment and the GitHub release stay in **manual-
promotion** mode (`automaticRelease=false` in `build.gradle.kts`, `--draft` on
`gh release create`) until the first few tagged releases prove the pipeline.
Promote from the Central Portal UI and via `gh release edit --draft=false` once
you've sanity-checked the artefacts side-by-side.

## Required secrets

Set these in the repository's *Settings → Secrets and variables → Actions*:

| Secret | Used by | Purpose |
|---|---|---|
| `APPLE_DEVELOPER_ID_P12` | `mac-helper` | Base64-encoded Developer ID certificate. |
| `APPLE_DEVELOPER_ID_P12_PASSWORD` | `mac-helper` | P12 password. |
| `APPLE_SIGNING_KEYCHAIN_PASSWORD` | `mac-helper` | Ad-hoc keychain password. |
| `APPLE_DEVELOPER_IDENTITY` | `mac-helper` | `Developer ID Application: …` codesign identity. |
| `APPLE_NOTARY_API_KEY` | `mac-helper` | Base64-encoded App Store Connect API key. |
| `APPLE_NOTARY_API_KEY_ID` | `mac-helper` | API key ID. |
| `APPLE_NOTARY_API_ISSUER` | `mac-helper` | API issuer UUID. |
| `MAVEN_CENTRAL_USERNAME` | `publish` | Sonatype Central Portal token username. |
| `MAVEN_CENTRAL_PASSWORD` | `publish` | Central Portal token. |
| `SIGNING_IN_MEMORY_KEY` | `publish` | ASCII-armored PGP private key (no header line stripping). |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | `publish` | PGP key passphrase. |

The Maven Central token comes from [Central Portal → View
Account → Generate User Token][user-token]. The PGP key must be exported
ASCII-armored (`gpg --armor --export-secret-key <key-id>`) and the public side
must already be uploaded to `keys.openpgp.org` (or another keyserver pair
Central trusts).

[user-token]: https://central.sonatype.com/account

## Local verification

`publishToMavenLocal` works without any credentials or signing keys — the
signing convention only fires when `ORG_GRADLE_PROJECT_signingInMemoryKey` is
set. The `:verifyMavenLocalPublication` task drives the full shape check:

```shell
# Publish all four modules + verify shape. Stub mac helper because Linux can't
# build the real one; cross-arch Linux helpers come from the real Rust build.
./gradlew verifyMavenLocalPublication \
    -PstubMacHelperForTesting \
    -PallLinuxArches
```

It asserts that each module ends up with:

- `<artifactId>-<version>.jar` (main jar)
- `<artifactId>-<version>-sources.jar`
- `<artifactId>-<version>-javadoc.jar` (empty — see "Open follow-ups" below)
- `<artifactId>-<version>.pom` with the Central-required POM elements
  (`<name>`, `<description>`, `<url>`, `<licenses>`, `<scm>`, `<developers>`)
- `<artifactId>-<version>.module` (Gradle Module Metadata)

For `:recording` it additionally asserts the main jar contains the expected
native helpers at:

- `native/macos/spectre-screencapture` (universal SCK helper)
- `native/linux/x86_64/spectre-wayland-helper`
- `native/linux/aarch64/spectre-wayland-helper`

If you have a real notarised mac helper on disk (e.g. downloaded from a
previous release-CI run), point at it instead of the stub:

```shell
./gradlew verifyMavenLocalPublication \
    -PprebuiltMacHelperPath=/path/to/SpectreScreenCapture \
    -PprebuiltLinuxHelpersDir=/path/to/linux-helpers
```

The `prebuiltLinuxHelpersDir` directory must contain
`x86_64/spectre-wayland-helper` and `aarch64/spectre-wayland-helper`.

## Coordinates

| Module | Coordinates |
|---|---|
| `:core` | `dev.sebastiano.spectre:spectre-core:<version>` |
| `:server` | `dev.sebastiano.spectre:spectre-server:<version>` |
| `:recording` | `dev.sebastiano.spectre:spectre-recording:<version>` |
| `:testing` | `dev.sebastiano.spectre:spectre-testing:<version>` |

The shared metadata (group, license, SCM, developer) lives in
[`gradle.properties`][root-properties] at the repo root; per-module
`POM_ARTIFACT_ID` / `POM_NAME` / `POM_DESCRIPTION` lives in each module's own
`gradle.properties`. `gradle.properties` is loaded as Latin-1, so the
descriptions stay ASCII-safe.

[root-properties]: https://github.com/rock3r/spectre/blob/main/gradle.properties

## Open follow-ups

- **Dokka HTML javadoc.** Currently `JavadocJar.Empty()` — satisfies Central's
  gate but doesn't give consumers offline API docs. Wire `JavadocJar.Dokka(...)`
  once Dokka 2 is verified against this codebase. Until then the canonical API
  docs live at https://spectre.sebastiano.dev.
- **Snapshot publishing.** The plumbing supports it (the default version is
  `0.1.0-SNAPSHOT`), but no CI workflow currently publishes snapshots — wire a
  `main`-push job if/when there's demand from consumers tracking unreleased
  changes.
- **Automatic Central promotion.** `automaticRelease=false` keeps the staging
  repo in manual-promote mode. Flip to `true` once the first published version
  is in the wild and we trust the pipeline.
