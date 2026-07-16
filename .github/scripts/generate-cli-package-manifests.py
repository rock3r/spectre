#!/usr/bin/env python3
"""Generate the self-hosted Homebrew tap formula and Scoop bucket manifest for a release."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


RELEASE_BASE = "https://github.com/rock3r/spectre/releases/download/v{version}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as archive:
        for chunk in iter(lambda: archive.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--macos-arm64", type=Path, required=True)
    parser.add_argument("--macos-x64", type=Path, required=True)
    parser.add_argument("--windows-x64", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    for archive in (args.macos_arm64, args.macos_x64, args.windows_x64):
        if not archive.is_file():
            raise SystemExit(f"Missing release archive: {archive}")

    output = args.output_dir
    formula = output / "Formula" / "spectre.rb"
    scoop = output / "bucket" / "spectre.json"
    formula.parent.mkdir(parents=True, exist_ok=True)
    scoop.parent.mkdir(parents=True, exist_ok=True)

    base = RELEASE_BASE.format(version=args.version)
    formula.write_text(
        f'''class Spectre < Formula
  desc "Agent-facing CLI and MCP server for Spectre Compose Desktop automation"
  homepage "https://github.com/rock3r/spectre"
  version "{args.version}"

  on_macos do
    if Hardware::CPU.arm?
      url "{base}/spectre-macosArm64.zip"
      sha256 "{sha256(args.macos_arm64)}"
    else
      url "{base}/spectre-macosX64.zip"
      sha256 "{sha256(args.macos_x64)}"
    end
  end

  def install
    app = Dir["spectre-cli-*/Spectre.app"].first
    odie "missing Spectre.app in release archive" if app.nil?
    libexec.install app
    bin.install_symlink libexec/"Spectre.app/Contents/MacOS/spectre"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/spectre --version")
  end
end
'''
    )
    scoop.write_text(
        f'''{{
    "version": "{args.version}",
    "description": "Agent-facing CLI and MCP server for Spectre Compose Desktop automation",
    "homepage": "https://github.com/rock3r/spectre",
    "license": "Apache-2.0",
    "architecture": {{
        "64bit": {{
            "url": "{base}/spectre-windowsX64.zip",
            "hash": "sha256:{sha256(args.windows_x64)}"
        }}
    }},
    "bin": "spectre-cli-$version/spectre.exe"
}}
'''
    )


if __name__ == "__main__":
    main()
