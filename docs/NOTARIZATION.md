# Notarization

Spectre ships the macOS ScreenCaptureKit recorder as `SpectreCaptureHelper.app` inside the
`spectre-recording-macos` jar. Distribution builds must **Developer ID sign, notarize, and
staple the full app bundle** before the jar is published, otherwise macOS Gatekeeper can reject
the extracted helper when a consumer starts `ScreenCaptureKitRecorder`.

TCC Screen Recording grants pin to **bundle ID + code signature**. Ad-hoc-signed local builds
lose Settings grants on every rebuild; release builds keep the grant across versions when the
same Developer ID identity and bundle id (`dev.sebastiano.spectre.screencapture`) are used.

Local development builds do not need Apple credentials. The notarization path is opt-in and is
only intended for release builds.

## What gets notarized

The release build creates a universal `arm64` + `x86_64` helper binary, packages it as:

```text
recording/build/generated/screenCaptureHelperUniversal/SpectreCaptureHelper.app
```

Gradle then:

1. Packages the universal Mach-O into `SpectreCaptureHelper.app` (Info.plist, `LSUIElement`,
   icon, nested `Contents/MacOS/spectre-screencapture`).
2. Developer ID signs the **bundle** with
   `codesign --sign <identity> --options runtime --timestamp --force --deep`.
3. Archives the signed app with `ditto -c -k --keepParent`.
4. Submits the archive with `xcrun notarytool submit --wait` (30 minute timeout).
5. Staples the ticket with `xcrun stapler staple` onto the `.app`.
6. Verifies with `codesign --verify --deep --strict` and `xcrun stapler validate`.
7. Best-effort `spctl -a -vv --type execute` (warn-only if the host policy database rejects it).
8. Stages the signed+stapled app into
   `native/macos/SpectreCaptureHelper.app` in the `spectre-recording-macos` jar resources.

Unlike bare command-line tools, app bundles support stapling and Gatekeeper assessment of the
distributed artifact.

## Local-dev signing story (ad-hoc vs release)

| Build | Signature | TCC behaviour | When to use |
|---|---|---|---|
| Default `./gradlew :recording:jar` | Ad-hoc (`codesign -s -`) on the host-arch app | Grant may bind to path/ad-hoc identity; re-grant after clean rebuilds | Day-to-day development |
| `-PuniversalHelper` without notarize | Ad-hoc universal app | Same as above | Fat-binary packaging smoke |
| `-PuniversalHelper -PnotarizeScreenCaptureKitHelper` | Developer ID + notary + staple | Stable Settings row for **Spectre Capture Helper**; grant survives updates with the same identity | Release / release smoke |

**Do not** Developer ID sign local iteration builds with a personal cert and then ship an ad-hoc
rebuild under the same install path without expecting a re-prompt — keep release identity on the
release path only.

For pre-tag release smoke, dispatch the artifact-only
[`notarize-macos.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/notarize-macos.yml)
workflow. It uses the same signing implementation as the tag release and uploads the notarized
helper plus both stapled CLI bundles without publishing anything. See
[Release smoke](RELEASE-SMOKE.md#on-demand-pre-tag-macos-notarization) for the command and local
B8/B16 verification steps.

## Local setup

Install Xcode or the Xcode Command Line Tools so these commands are available:

```bash
codesign --version
xcrun notarytool --version
xcrun stapler --version
```

Create and install a Developer ID Application certificate through Apple Developer, then confirm
Keychain can see it:

```bash
security find-identity -v -p codesigning
```

The identity should look like:

```text
Developer ID Application: Example Developer (TEAMID)
```

Store notarization credentials in Keychain once. Let `notarytool` prompt for the app-specific
password:

```bash
xcrun notarytool store-credentials <notary-profile> \
  --apple-id "developer@example.com" \
  --team-id "TEAMID"
```

Do not pass the app-specific password to Gradle or `notarytool` on the command line. macOS
process listings include command arguments while a submission is running.

## Local release smoke

Add non-secret release properties to `~/.gradle/gradle.properties`:

```properties
compose.desktop.mac.signing.identity=Developer ID Application: Example Developer (TEAMID)
compose.desktop.mac.notarization.keychainProfile=<notary-profile>
```

Then run:

```bash
./gradlew :recording:assembleScreenCaptureKitHelperUniversal \
  -PuniversalHelper \
  -PnotarizeScreenCaptureKitHelper
```

If Apple accepts the submission, the build staples the ticket, verifies the app signature, and
stages the app into jar resources. If Apple keeps the submission in `In Progress` past the
timeout, the Gradle task fails locally but the submission continues processing on Apple's side.
Query it with:

```bash
xcrun notarytool info <submission-id> --keychain-profile <notary-profile>
```

### Quarantined Gatekeeper check

After a successful local or CI notarization, validate as a user would:

```bash
APP=recording/build/generated/screenCaptureHelper/native/macos/SpectreCaptureHelper.app
# Simulate download quarantine
xattr -w com.apple.quarantine "0081;00000000;Safari;..." "$APP"   # or copy via browser/zip
codesign --verify --deep --strict --verbose=2 "$APP"
xcrun stapler validate "$APP"
spctl -a -vv --type execute "$APP"
```

## CI release workflow

The tag workflow at
[`.github/workflows/release.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/release.yml)
builds the universal helper on `macos-latest`, imports the Developer ID certificate into a
temporary keychain, signs / notarizes / staples `SpectreCaptureHelper.app`, and uploads the
notarized app artifact for the publish job to package into `spectre-recording-macos`.

Set these repository secrets:

| Secret | Purpose |
|---|---|
| `APPLE_DEVELOPER_ID_P12` | Base64-encoded Developer ID Application `.p12`. |
| `APPLE_DEVELOPER_ID_P12_PASSWORD` | Password for that `.p12` export. |
| `APPLE_SIGNING_KEYCHAIN_PASSWORD` | Temporary CI keychain password. Use a long random value. |
| `APPLE_DEVELOPER_IDENTITY` | Exact `codesign` identity, for example `Developer ID Application: Example Developer (TEAMID)`. |
| `APPLE_NOTARY_API_KEY` | Base64-encoded App Store Connect API `.p8` key. |
| `APPLE_NOTARY_API_KEY_ID` | App Store Connect API key ID. |
| `APPLE_NOTARY_API_ISSUER` | App Store Connect API issuer UUID. |

Use App Store Connect API key auth in CI so no app-specific password is passed as a process
argument. The workflow writes the `.p8` key to `$RUNNER_TEMP` and passes only the file path,
key id, and issuer to Gradle.

## Rotation

To rotate the Developer ID certificate:

1. Create or renew a Developer ID Application certificate in Apple Developer.
2. Export it from Keychain Access as a password-protected `.p12`.
3. Base64-encode it with `base64 -i DeveloperID.p12 | pbcopy`.
4. Update `APPLE_DEVELOPER_ID_P12`, `APPLE_DEVELOPER_ID_P12_PASSWORD`, and
   `APPLE_DEVELOPER_IDENTITY` in the repository secrets.
5. Note: TCC grants bound to the old Team ID may require users to re-approve once after a
   Team ID change.

To rotate notarization credentials:

1. Create a new App Store Connect API key with access to notarization.
2. Base64-encode the `.p8` file.
3. Update `APPLE_NOTARY_API_KEY`, `APPLE_NOTARY_API_KEY_ID`, and
   `APPLE_NOTARY_API_ISSUER`.
4. Push a test tag and verify the release workflow reaches the `stapler validate` step.

## Validation

After a notarized release, validate on a fresh macOS user or machine:

```bash
codesign --verify --deep --strict --verbose=2 SpectreCaptureHelper.app
xcrun stapler validate SpectreCaptureHelper.app
spctl -a -vv --type execute SpectreCaptureHelper.app
```

Then run a small `ScreenCaptureKitRecorder` scenario (or `spectre permissions check`) from the
released jar. The Settings row should show **Spectre Capture Helper**, and Gatekeeper should not
reject the extracted app.
