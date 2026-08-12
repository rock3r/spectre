#!/usr/bin/env python3
"""Structural contracts for the IDE UI test workflow."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ide-uitest.yml"
DIRECT_REPOSITORY_FLAG = "-Porg.jetbrains.intellij.platform.useCacheRedirector=false"
FORBIDDEN_SYSTEM_PROPERTY = "-Dorg.jetbrains.intellij.platform.useCacheRedirector=false"


def main() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    if FORBIDDEN_SYSTEM_PROPERTY in text:
        raise SystemExit("Cache Redirector setting must be a Gradle project property (-P), not -D")
    count = text.count(DIRECT_REPOSITORY_FLAG)
    if count != 2:
        raise SystemExit(
            "ide-uitest.yml must disable JetBrains Cache Redirector on both Gradle "
            f"entrypoints (found {count})"
        )
    print("test-ide-uitest-workflow: OK")


if __name__ == "__main__":
    main()
