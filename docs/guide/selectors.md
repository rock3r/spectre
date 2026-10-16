# Finding nodes

Selectors locate Compose nodes in the semantics tree. `ComposeAutomator` provides selectors
for test tags, text, content descriptions, and roles.

!!! note "All selectors are non-waiting"
    Each `findBy…`, `findOneBy…`, `hasTag`, or `hasText` call reads the current semantics
    state once. Use [`waitForNode(...)`](synchronization.md#waitfornode) when a node
    may appear later.

## By test tag

```kotlin
val nodes: List<AutomatorNode> = automator.findByTestTag("Submit")
val node: AutomatorNode? = automator.findOneByTestTag("Submit")
```

A test tag gives a test a stable selector when the composable defines
`Modifier.testTag("...")`. Prefer it for elements the test will interact with.

```kotlin
@Composable
fun MyButton() {
    Button(
        onClick = { /* … */ },
        modifier = Modifier.testTag("Submit"),
    ) {
        Text("Submit")
    }
}
```

## By visible text

```kotlin
val nodes = automator.findByText("Submit")            // exact, case-sensitive
val nodes = automator.findByText("Sub", exact = false) // substring, case-insensitive
val node  = automator.findOneByText("Submit")
```

Use `TextQuery` when a text match needs explicit options:

```kotlin
import dev.sebastiano.spectre.core.TextQuery

val nodes = automator.findByText(
    TextQuery.exact("Submit", ignoreCase = true)
)
```

`TextQuery` supports exact, substring, and case-insensitive matching.
Text selectors can fail after copy or translation changes. Add test tags when the UI is under test control.

## By content description

```kotlin
val nodes = automator.findByContentDescription("Send message")
```

This selector reads `Modifier.semantics { contentDescription = "..." }`.
Use it for icon buttons that have no visible text and expose an accessibility description.

## By role

```kotlin
import androidx.compose.ui.semantics.Role

val buttons = automator.findByRole(Role.Button)
val checkboxes = automator.findByRole(Role.Checkbox)
```

Roles come from `Modifier.semantics { role = Role.Button }` or standard Material and
Compose components. A role query can check all buttons in a dialog.
Use a test tag when a test needs one specific button.

## Presence checks

Use the presence helpers when a test needs a Boolean result, such as a popup state:

```kotlin
import dev.sebastiano.spectre.core.TextQuery

val popupOpen: Boolean = automator.hasTag("popup.body")
val showsSubmit: Boolean = automator.hasText("Submit")
val showsPartial: Boolean = automator.hasText("Sub", exact = false)
val caseInsensitive: Boolean = automator.hasText(TextQuery.exact("submit", ignoreCase = true))
```

Presence helpers read one snapshot and do **not** call `refreshWindows()`.
Call `refreshWindows()` or `tree()` after a popup window appears or closes.
To wait for a popup to close, use [`waitUntilGone`](synchronization.md#waituntilgone).
It refreshes windows before every poll.

## Working with the result

`AutomatorNode` exposes these properties:

| Property                    | Meaning                                                           |
| --------------------------- | ----------------------------------------------------------------- |
| `node.testTag`              | The `Modifier.testTag` value, or `null`.                          |
| `node.text`                 | First text on the node, or `null`.                                |
| `node.texts`                | All text strings on the node.                                     |
| `node.contentDescriptions`  | All content-description strings.                                  |
| `node.contentDescription`   | First content-description string, or `null`.                      |
| `node.role`                 | The semantics role, or `null`.                                    |
| `node.isFocused`            | Focused state.                                                    |
| `node.isDisabled`           | Disabled state.                                                   |
| `node.isSelected`           | Selected state (toggleable/radio).                              |
| `node.editableText`         | Current text-field value.                                         |
| `node.boundsInWindow`       | Layout bounds within the window's Compose surface.                |
| `node.boundsOnScreen`       | Bounds in screen coordinates (HiDPI-corrected).                   |
| `node.centerOnScreen`       | Centre point in screen coordinates — what input helpers click.    |
| `node.children`             | Child nodes in the semantics tree.                                |
| `node.parent`               | Parent node in the semantics tree, or `null` at a root.           |

Use `boundsOnScreen` and `centerOnScreen` for screenshot regions or custom input.
The [`HiDpiMapper`](https://github.com/rock3r/spectre/blob/main/core/src/main/kotlin/dev/sebastiano/spectre/core/HiDpiMapper.kt)
handles macOS Retina, Windows DPI scaling (100/125/150/200%), and mixed-DPI multi-monitor
setups.

## Walking the tree by hand

Call [`automator.tree()`](automator.md#surfaces-and-the-semantics-tree) when a selector
cannot express the query. Inspect the returned tree directly:

```kotlin
val secondWindow = automator.tree(windowIndex = 1)
val matching = secondWindow.allNodes()
    .filter { it.isFocused && it.role == Role.Button }
```

`AutomatorWindow.allNodes()` returns every window node in tree order.
`AutomatorWindow.roots()` returns top-level nodes. Traverse `node.children` to inspect
their descendants.

## Debugging

Print the tree when a selector returns an unexpected result:

```kotlin
println(automator.printTree())
```

The output lists windows, nodes, tags, text, and roles.
Check whether the expected node has composed and whether its actual semantics match the query.

If `printTree()` returns an empty string, the composition may have failed before registering
a node. Check test JVM stderr for EDT or composition exceptions.
Those exceptions may not reach the test method.
