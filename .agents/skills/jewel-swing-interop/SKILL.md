---
name: jewel-swing-interop
description: Use when working on IntelliJ/Jewel popup hosting, ComposePanel embedding, SwingBridgeTheme, popup rendering modes, or AWT/Compose coordinate conversion.
metadata:
  internal: true
---

# Jewel Swing Interop

Use this skill when the task touches Jewel, Swing-hosted Compose, IntelliJ tool windows, or popup
behavior.

## Highest-Level API First

Prefer the simplest valid integration point:

1. `ToolWindow.addComposeTab(...)` for IntelliJ tool windows
2. `JewelComposePanel(...)` or equivalent bridge wrappers for generic Swing hosting
3. lower-level `ComposePanel` wiring only when higher-level bridge APIs do not fit

## Theme And Hosting Rules

- In IntelliJ plugin contexts, prefer `SwingBridgeTheme`.
- Avoid unmanaged extra `ComposePanel` wrappers when a bridge utility already exists.
- Reach for `LocalComponent` when you need the host Swing component in Compose.

## Popup Caveats That Matter For Spectre

- `JewelFlags.useCustomPopupRenderer` defaults to `false`.
- When custom popup rendering is disabled, Jewel falls back to normal Compose popup behavior.
- Compose Desktop defaults `compose.layers.type` to `OnSameCanvas`.
- Because of that, popup discovery cannot assume every popup creates its own AWT window.

Always consider all of these when investigating popup semantics, hit-testing, or window discovery:

1. same-window extra semantics roots
2. Swing-hosted `ComposePanel` layers
3. real owned windows such as `JDialog` / `JBPopup`

## Synthetic Key Dispatch Guidance

- Compose Desktop key listeners usually live on the inner Skiko canvas (`SkiaLayer` /
  `SkiaSwingLayer`), not on the outer `ComposePanel` / `ComposeWindowPanel` wrapper.
- When debugging synthetic typing in Jewel or Swing-hosted Compose, do not assume
  `Window.focusOwner` is available. macOS `apple.awt.UIElement=true` helper JVMs can
  keep every AWT window unfocused while Compose's internal focus model still has a
  focused `TextField`.
- Spectre's synthetic driver should therefore target the key-listening descendant under
  the pointer target or Compose host and use `KeyboardFocusManager.redispatchEvent`, not
  plain `Component.dispatchEvent`, for key events generated outside the OS keyboard path.

## Coordinate And Interop Guidance

- Be careful when moving between Compose coordinates and AWT screen coordinates.
- Prefer established prior art from IntelliJ remote-driver and Compose Desktop internals over
  ad-hoc math.
- When a conversion seems obvious, verify it against existing host code before locking it in.

## Local References

Read these checked-out sources when implementing:

1. `/Users/rock3r/src/intellij-community/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/ToolWindowExtensions.kt`
2. `/Users/rock3r/src/intellij-community/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/JewelComposePanelWrapper.kt`
3. `/Users/rock3r/src/intellij-community/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/theme/SwingBridgeTheme.kt`
4. `/Users/rock3r/src/intellij-community/platform/jewel/ui/src/main/kotlin/org/jetbrains/jewel/ui/component/Popup.kt`
5. `/Users/rock3r/src/intellij-community/platform/jewel/foundation/src/main/kotlin/org/jetbrains/jewel/foundation/JewelFlags.kt`
6. `/Users/rock3r/src/intellij-community/plugins/performanceTesting/remote-driver.compose/src/com/intellij/performanceTesting/remoteDriver/compose/ComposeXpathDataModelExtension.kt`
