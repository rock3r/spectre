#!/usr/bin/env python3
"""Fail-closed descriptions of the stock GNOME 42 and GNOME 46 portal pickers."""
from __future__ import annotations


def folded(value: str) -> str:
    return " ".join((value or "").split()).casefold()


def is_dialog(role: str, name: str) -> bool:
    return role in ("dialog", "frame") and folded(name) == "remote desktop"


def _indexed(facts: list[dict]) -> list[dict]:
    return [dict(item, index=item.get("index", index)) for index, item in enumerate(facts)]


def _ancestor(item: dict, facts: list[dict], predicate) -> bool:
    by_index = {candidate["index"]: candidate for candidate in facts}
    parent, seen = item.get("parent"), set()
    while parent is not None and parent not in seen:
        seen.add(parent)
        candidate = by_index.get(parent)
        if candidate is None:
            return False
        if predicate(candidate):
            return True
        parent = candidate.get("parent")
    return False


def _usable(item: dict) -> bool:
    states = set(item["states"])
    return "sensitive" in states and "visible" in states


def share_buttons(facts: list[dict]) -> list[dict]:
    facts = _indexed(facts)
    result = []
    for item in facts:
        if item["role"] != "push button" or folded(item["name"]) not in ("share", "_share share"):
            continue
        if [action.casefold() for action in item["actions"]] != ["click"]:
            continue
        if folded(item["name"]) == "_share share" and not any(
                child.get("parent") == item["index"] and child["role"] == "label" and
                folded(child["name"]) == "share" for child in facts):
            continue
        result.append(item)
    return result


def remote_interaction_controls(facts: list[dict]) -> list[dict]:
    facts = _indexed(facts)
    controls = []
    for item in facts:
        if item["role"] not in ("check box", "toggle button", "switch"):
            continue
        if [action.casefold() for action in item["actions"]] != ["toggle"]:
            continue
        named = folded(item["name"]) == "allow remote interaction"
        in_named_row = _ancestor(item, facts, lambda parent:
                                 parent["role"] in ("list item", "list box row", "table row") and
                                 folded(parent["name"]) == "allow remote interaction")
        if named or in_named_row:
            controls.append(item)
    return controls


def selected_noble_sources(facts: list[dict]) -> list[dict]:
    return [item for item in _indexed(facts)
            if item["role"] in ("toggle button", "radio button")
            and folded(item["name"]) != "allow remote interaction"]


def jammy_monitor_rows(facts: list[dict]) -> list[dict]:
    """Return only selected physical rows in GNOME 42's monitor GtkListBox."""
    facts = _indexed(facts)
    excluded = {"", "allow remote interaction", "remember this selection", "virtual monitor"}
    return [item for item in facts
            if item["role"] in ("list item", "list box row", "table row")
            and folded(item["name"]) not in excluded
            and (item.get("parent") is None or _ancestor(
                item, facts, lambda parent: parent["role"] == "list"))]


def selection(facts: list[dict]) -> dict | None:
    """Describe the exact controls to use, keyed by stable snapshot indices."""
    facts = _indexed(facts)
    shares, interaction = share_buttons(facts), remote_interaction_controls(facts)
    if (len(shares) != 1 or len(interaction) != 1 or
            not _usable(shares[0]) or not _usable(interaction[0])):
        return None
    if (interaction[0]["role"] == "check box" and
            folded(interaction[0]["name"]) == "allow remote interaction"):
        sources, profile_name = selected_noble_sources(facts), "gnome-46"
    else:
        sources, profile_name = jammy_monitor_rows(facts), "gnome-42"
    if (len(sources) != 1 or not _usable(sources[0]) or
            not set(sources[0]["states"]) & {"checked", "selected", "pressed"}):
        return None
    return {"profile": profile_name, "share": shares[0]["index"],
            "interaction": interaction[0]["index"], "source": sources[0]["index"]}


def profile(facts: list[dict]) -> str | None:
    matched = selection(facts)
    return matched["profile"] if matched else None
