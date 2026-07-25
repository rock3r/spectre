package dev.sebastiano.spectre.recording.screencapturekit

/**
 * Stable macOS app-bundle identity for the ScreenCaptureKit helper (#190).
 *
 * TCC Screen Recording grants accrue to this bundle when preflight/capture/request run the
 * executable nested under `Contents/MacOS/`. Settings shows [DISPLAY_NAME] + icon, not the spawning
 * terminal/JVM.
 */
public object HelperAppBundle {
    /** On-disk `.app` directory name (no spaces — stable install path). */
    public const val APP_DIR_NAME: String = "SpectreCaptureHelper.app"

    /** CFBundleDisplayName / CFBundleName. */
    public const val DISPLAY_NAME: String = "Spectre Capture Helper"

    /** CFBundleIdentifier — TCC pin target together with code signature. */
    public const val BUNDLE_ID: String = "dev.sebastiano.spectre.screencapture"

    /** CFBundleExecutable and the nested Mach-O filename. */
    public const val EXECUTABLE_NAME: String = "spectre-screencapture"

    /** Relative path from the `.app` root to the executable. */
    public const val EXECUTABLE_RELATIVE_PATH: String = "Contents/MacOS/$EXECUTABLE_NAME"

    /** Classpath root for the staged bundle tree inside the recording jar. */
    public const val RESOURCE_ROOT: String = "native/macos/$APP_DIR_NAME"

    /** Classpath path of the nested executable resource. */
    public const val EXECUTABLE_RESOURCE_PATH: String = "$RESOURCE_ROOT/$EXECUTABLE_RELATIVE_PATH"

    /** Classpath path of Info.plist. */
    public const val INFO_PLIST_RESOURCE_PATH: String = "$RESOURCE_ROOT/Contents/Info.plist"

    /** Classpath path of PkgInfo. */
    public const val PKG_INFO_RESOURCE_PATH: String = "$RESOURCE_ROOT/Contents/PkgInfo"

    /** Classpath path of the app icon. */
    public const val ICON_RESOURCE_PATH: String = "$RESOURCE_ROOT/Contents/Resources/AppIcon.icns"
}
