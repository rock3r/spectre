# Finding nodes

Selectors are how you locate a Compose node in the semantics tree. The `ComposeAutomator`
exposes four families of selectors plus a handful of convenience overloads.

!!! note "All selectors are non-waiting"
    Every `findBy…` / `findOneBy…` call is a single read against the current semantics
    state. If you need to wait for a node to appear, use
    [`waitForNode(...)`](synchronization.md#waitfornode) instead.

## By test tag

```kotlin
val nodes: List<AutomatorNode> = automator.findByTestTag("Submit")
val node: AutomatorNode? = automator.findOneByTestTag("Submit")
```

This is the most reliable selector — it relies on a deliberate
`Modifier.testTag("...")` on the composable. Use it as your default.

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

Or with the structured `TextQuery`:

```kotlin
import dev.sebastiano.spectre.core.TextQuery

val nodes = automator.findByText(
    TextQuery.exact("Submit", ignoreCase = true)
)
```

`TextQuery` covers `exact`, `substring`, and case-insensitive variants. Prefer test tags
when the UI is yours — text matchers are brittle to copy changes and localisation.

## By content description

```kotlin
val nodes = automator.findByContentDescription("Send message")
```

Matches `Modifier.semantics { contentDescription = "..." }` — the Compose accessibility
hook. Use this for icon-only buttons that have no visible text but should be announced
to assistive tech.

## By role

```kotlin
import androidx.compose.ui.semantics.Role

val buttons = automator.findByRole(Role.Button)
val checkboxes = automator.findByRole(Role.Checkbox)
```

Roles come from `Modifier.semantics { role = Role.Button }` (or, more often, are set
implicitly by the standard Material/Compose components). Use this for "all buttons in
this dialog" style assertions, not as a primary selector.

## Working with the result

`AutomatorNode` exposes the bits you usually want:

| Property                    | Meaning                                                           |
| --------------------------- | ----------------------------------------------------------------- |
| `node.testTag`              | The `Modifier.testTag` value, or `null`.                          |
| `node.text`                 | First text on the node, or `null`.                                |
| `node.texts`                | All text strings on the node.                                     |
| `node.contentDescriptions`  | All content-description strings.                                  |
| `node.role`                 | The semantics role, or `null`.                                    |
| `node.isFocused`            | Focused state.                                                    |
| `node.isDisabled`           | Disabled state.                                                   |
| `node.isSelected`           | Selected state (toggleable / radio).                              |
| `node.editableText`         | Current text-field value.                                         |
| `node.boundsInWindow`       | Layout bounds within the window's Compose surface.                |
| `node.boundsOnScreen`       | Bounds in screen coordinates (HiDPI-corrected).                   |
| `node.centerOnScreen`       | Centre point in screen coordinates — what input helpers click.    |
| `node.children`             | Child nodes in the semantics tree.                                |

Use `boundsOnScreen` and `centerOnScreen` for screenshot regions or custom input. The
[`HiDpiMapper`](https://github.com/rock3r/spectre/blob/main/core/src/main/kotlin/dev/sebastiano/spectre/core/HiDpiMapper.kt)
handles macOS Retina, Windows DPI scaling (100/125/150/200%), and mixed-DPI multi-monitor
setups.

## Walking the tree by hand

When you need more than the canonical selectors, drop down to
[`automator.tree()`](automator.md#surfaces-and-the-semantics-tree) and walk it directly:

```kotlin
val secondWindow = automator.tree(windowIndex = 1)
val matching = secondWindow.allNodes()
    .filter { it.isFocused && it.role == Role.Button }
```

`AutomatorWindow.allNodes()` returns every node in the window in tree order.
`AutomatorWindow.roots()` returns the top-level nodes only; from there you can recurse
through `node.children` to walk the tree manually.

## Debugging

If a selector doesn't return what you expect, dump the tree:

```kotlin
println(automator.printTree())
```

You'll see every window, every node, and the test tags / text / roles attached to them.
This is usually the fastest way to find out whether the node simply isn't where you
think it is, or hasn't been composed yet.
