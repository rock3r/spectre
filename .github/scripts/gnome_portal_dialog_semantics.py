#!/usr/bin/env python3
"""Fail-closed descriptions of the stock GNOME 42 and GNOME 46 portal pickers."""
from __future__ import annotations


def folded(value: str) -> str:
    return " ".join((value or "").split()).casefold()


def is_dialog(role: str, name: str) -> bool:
    return role in ("dialog", "frame") and folded(name) == "remote desktop"


def profile(facts: list[dict]) -> str | None:
    """Return a supported stock layout only when its controls are unambiguous."""
    shares = [item for item in facts
              if item["role"] == "push button" and folded(item["name"]) == "share"]
    interaction = remote_interaction_controls(facts)
    if len(shares) != 1 or len(interaction) != 1:
        return None
    role = interaction[0]["role"]
    if role == "check box":
        sources = selected_noble_sources(facts)
        return "gnome-46" if len(sources) == 1 else None
    if role in ("toggle button", "switch"):
        sources = jammy_monitor_rows(facts)
        return "gnome-42" if len(sources) == 1 else None
    return None


def remote_interaction_controls(facts: list[dict]) -> list[dict]:
    named = [item for item in facts
             if folded(item["name"]) == "allow remote interaction"
             and item["role"] in ("check box", "toggle button", "switch")]
    if named:
        return named
    # GNOME 42 puts a GtkSwitch in an AdwActionRow. Some GTK/AT-SPI combinations
    # expose the row title as a separate label rather than as the switch's name.
    labels = [item for item in facts
              if folded(item["name"]) == "allow remote interaction"]
    switches = [item for item in facts
                if item["role"] in ("toggle button", "switch")
                and "toggle" in {action.casefold() for action in item["actions"]}]
    return switches if len(labels) == 1 and len(switches) == 1 else []


def selected_noble_sources(facts: list[dict]) -> list[dict]:
    return [item for item in facts
            if item["role"] in ("toggle button", "radio button")
            and set(item["states"]) & {"checked", "selected", "pressed"}
            and folded(item["name"]) != "allow remote interaction"]


def jammy_monitor_rows(facts: list[dict]) -> list[dict]:
    """GNOME 42's ScreenCastWidget uses one selectable GtkListBox monitor row."""
    excluded = {"", "allow remote interaction", "remember this selection", "virtual monitor"}
    return [item for item in facts
            if item["role"] in ("list item", "list box row", "table row")
            and folded(item["name"]) not in excluded
            and set(action.casefold() for action in item["actions"]) &
                {"activate", "click", "press", "select"}]
