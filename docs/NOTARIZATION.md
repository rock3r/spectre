# Notarization

Spectre ships the macOS ScreenCaptureKit recorder as a small Swift command-line helper inside
the `recording` jar. Distribution builds must Developer ID sign and notarize that helper before
the jar is published, otherwise macOS Gatekeeper can reject the extracted helper when a consumer
starts `ScreenCaptureKitRecorder`.

Local development builds do not need Apple credentials. The notarization path is opt-in and is
only intended for release builds.

## What gets notarized

The release build creates a universal `arm64` + `x86_64` helper binary at:

```text
recording/build/generated/screenCaptureHelperUniversal/SpectreScreenCapture
```

Gradle then:

1. Signs the helper with `codesign --options runtime --timestamp --force`.
2. Archives the signed helper with `ditto -c -k --keepParent`.
3. Submits the archive with `xcrun notarytool submit`.
4. Waits for Apple to finish processing, bounded by a 30 minute timeout.
5. Verifies the resulting Developer ID signature with `codesign --verify --strict --verbose=4`
   before staging it into `native/macos/spectre-screencapture` in the jar resources.

Apple's notary service can issue a ticket for a standalone binary inside the submitted archive,
but `xcrun stapler` cannot currently staple tickets directly to bare command-line executables,
and `spctl --assess --type execute` reports bare tools as valid code that is not an app.
Spectre therefore notarizes the helper and verifies the Developer ID signature locally, but does
not run `stapler` or use `spctl` as the Gradle gate for `SpectreScreenCapture`.

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
./gradlew :recording:jar -PuniversalHelper -PnotarizeScreenCaptureKitHelper
```

If Apple accepts the submission, the build verifies the helper's signature and packages the jar.
If Apple keeps the submission in `In Progress` past the timeout, the Gradle task fails locally but
the submission continues processing on Apple's side. Query it with:

```bash
xcrun notarytool info <submission-id> --keychain-profile <notary-profile>
```

## CI release workflow

The tag workflow at
[`.github/workflows/release.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/release.yml)
builds the universal helper on `macos-latest`, imports the Developer ID certificate into a
temporary keychain, signs and notarizes the helper, and uploads the recording jar to the GitHub
release.

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

To rotate notarization credentials:

1. Create a new App Store Connect API key with access to notarization.
2. Base64-encode the `.p8` file.
3. Update `APPLE_NOTARY_API_KEY`, `APPLE_NOTARY_API_KEY_ID`, and
   `APPLE_NOTARY_API_ISSUER`.
4. Push a test tag and verify the release workflow reaches the `codesign --verify` step.

## Validation

After a notarized release, validate on a fresh macOS user or machine:

```bash
codesign --verify --strict --verbose=2 spectre-screencapture
```

Then run a small `ScreenCaptureKitRecorder` scenario from the released jar. The expected user
experience is a normal Screen Recording permission prompt if the host process is not already
trusted, and no Gatekeeper rejection for the extracted helper.
