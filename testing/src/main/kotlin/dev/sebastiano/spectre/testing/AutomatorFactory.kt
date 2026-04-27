package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator

/**
 * Factory for building a [ComposeAutomator] inside a test fixture.
 *
 * The default `{ ComposeAutomator.inProcess() }` is suitable for live UI tests against a real AWT
 * display. Headless or unit-style tests that only need the test ergonomics (rule/extension
 * plumbing) without a real Robot/EDT can supply a custom factory that returns a stub or a fake.
 */
typealias AutomatorFactory = () -> ComposeAutomator
