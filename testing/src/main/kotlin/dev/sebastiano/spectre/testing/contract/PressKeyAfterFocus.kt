package dev.sebastiano.spectre.testing.contract

/**
 * Activate the window hosting [fieldKey] ([AutomatorContractDriver.focusWindow], #364), click the
 * field, then [AutomatorContractDriver.pressKey], retrying when the target JVM has not yet acquired
 * OS keyboard focus.
 *
 * On macOS under JBR, a single Robot click can leave Compose focus updated while OS keyboard focus
 * is still settling (or briefly lost to the attacher JVM). The agent refuses `pressKey` in that
 * window with an `inputRejected` / "OS keyboard focus" error. Raising the window via focusWindow,
 * re-click, and short backoff makes the matrix cell durable without soft-skipping the keyboard
 * path.
 *
 * The whole path is opt-in off CI behind [RealKeyboardGate] (#449): it steals OS keyboard focus, so
 * it cannot run on a machine someone is using.
 */
public object PressKeyAfterFocus {
    /** Substring present in agent focus-rejection messages (typeText and pressKey). */
    public const val OS_KEYBOARD_FOCUS_MARKER: String =
        "target JVM does not currently own OS keyboard focus"

    /** Default AWT `KeyEvent.VK_TAB`. */
    public const val DEFAULT_KEY_CODE_TAB: Int = 9

    /** [AutomatorContractCorpus] scenario id this helper backs. */
    public const val SCENARIO_ID: String = "press-key-tab-after-focus"

    private const val DEFAULT_MAX_ATTEMPTS: Int = 8
    private const val BASE_SLEEP_MS: Long = 50L

    public fun run(
        driver: AutomatorContractDriver,
        fieldKey: String,
        keyCode: Int = DEFAULT_KEY_CODE_TAB,
        modifiers: Int = 0,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        sleepMs: (attemptIndex: Int) -> Long = { attempt -> BASE_SLEEP_MS * (attempt + 1) },
        sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
        gateEnabled: Boolean = RealKeyboardGate.isEnabled(),
        warn: (String) -> Unit = { message -> System.err.println(message) },
    ): String {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        if (!gateEnabled) {
            // Do not touch the driver at all. focusWindow and click are themselves focus-stealing,
            // so "try it and tolerate the failure" would still disrupt whoever is using the
            // machine — the exact thing the gate exists to prevent.
            warn(
                "Skipped contract corpus scenario `$SCENARIO_ID`: it raises the fixture window, " +
                    "clicks the text field, and sends real Robot key code $keyCode, so it needs " +
                    "the fixture window to own OS keyboard focus for the whole run (#449). " +
                    RealKeyboardGate.ENABLE_HINT
            )
            return RealKeyboardGate.SKIPPED_DETAIL
        }
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            // #364: expressible remediation for the pressKey focus-rejection error text.
            driver.focusWindow(fieldKey)
            driver.click(fieldKey)
            sleeper(sleepMs(attempt))
            try {
                driver.pressKey(keyCode = keyCode, modifiers = modifiers)
                return "pressKey=VK_$keyCode attempts=${attempt + 1}"
            } catch (ex: IllegalStateException) {
                // error() path from drivers/corpus helpers and some SpectreAgentException wraps.
                if (!isOsKeyboardFocusRejection(ex)) throw ex
                lastError = ex
            } catch (ex: java.io.IOException) {
                // AttachedAutomator.pressKey is @Throws(IOException::class).
                if (!isOsKeyboardFocusRejection(ex)) throw ex
                lastError = ex
            }
        }
        val detail = lastError?.message ?: "unknown focus rejection"
        // Hosted macOS + JBR (and sometimes Temurin) can prove Compose focus while OS
        // keyboard focus never settles — same class as typeText soft-skip in
        // AgentAttachIntegrationTest. Soft-pass only on macOS CI where Agent PressKey is
        // Experimental; Linux Xvfb remains Supported and must fail closed if retries exhaust.
        // Locally (any OS), hard-fail so developers see the gap.
        if (isCi() && isMacOs()) {
            return "skipped:os-keyboard-focus-after-$maxAttempts-attempts:$detail"
        }
        error("pressKey after focus failed after $maxAttempts attempts: $detail")
    }

    public fun isCi(): Boolean =
        !System.getenv("CI").isNullOrBlank() || !System.getenv("GITHUB_ACTIONS").isNullOrBlank()

    public fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    public fun isOsKeyboardFocusRejection(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        return msg.contains(OS_KEYBOARD_FOCUS_MARKER, ignoreCase = true) ||
            (msg.contains("inputRejected", ignoreCase = true) &&
                msg.contains("keyboard focus", ignoreCase = true))
    }
}
