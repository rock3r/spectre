package dev.sebastiano.spectre.sample.scenarios

/**
 * Catalogue of every scenario shipped by the sample harness.
 *
 * The order here is the order rendered in the picker. New scenarios should be appended at the end
 * so existing test recipes can keep referring to entries by index without breaking.
 */
val ALL_SCENARIOS: List<Scenario> =
    listOf(
        CounterScenario,
        PopupScenario,
        MultiWindowScenario,
        FocusScenario,
        ScrollScenario,
        HiDpiScenario,
    )
