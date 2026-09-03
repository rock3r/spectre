package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator

/**
 * Factory for building a [ComposeAutomator] inside a test fixture.
 *
 * The default `{ ComposeAutomator.inProcess() }` uses synthetic AWT input against a live window
 * hierarchy. Headless or unit-style tests that only need the test ergonomics (rule/extension
 * plumbing) without a display can supply a custom factory that returns a stub or
 * `RobotDriver.headless()`. Pass `RobotDriver()` through a custom factory to opt into real OS
 * input.
 */
public typealias AutomatorFactory = () -> ComposeAutomator
