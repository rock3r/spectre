#!/usr/bin/env python3
"""Sign and verify Mach-O members of the shaded CLI jar and its nested jars."""

from __future__ import annotations

import copy
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


HELPER_PREFIX = "native/macos/SpectreCaptureHelper.app/"
MACH_O_MAGICS = {
    b"\xfe\xed\xfa\xce",
    b"\xce\xfa\xed\xfe",
    b"\xfe\xed\xfa\xcf",
    b"\xcf\xfa\xed\xfe",
    b"\xca\xfe\xba\xbe",
    b"\xbe\xba\xfe\xca",
    b"\xca\xfe\xba\xbf",
    b"\xbf\xba\xfe\xca",
}


def is_mach_o(path: Path) -> bool:
    file_command = shutil.which("file")
    if file_command is None:
        raise RuntimeError("file is required to identify native jar entries")
    result = subprocess.run(
        [file_command, "-b", path],
        check=True,
        capture_output=True,
        text=True,
    )
    return "Mach-O" in result.stdout


def _write_archive(
    source: zipfile.ZipFile,
    destination: zipfile.ZipFile,
    updates: dict[int, bytes],
) -> None:
    destination.comment = source.comment
    for index, info in enumerate(source.infolist()):
        content = updates[index] if index in updates else source.read(info)
        destination.writestr(copy.copy(info), content)


def _signed_members(
    source: zipfile.ZipFile,
    temp_directory: Path,
    entitlements: str,
    identity: str,
    archive_name: str,
) -> dict[int, bytes]:
    updates: dict[int, bytes] = {}
    for index, info in enumerate(source.infolist()):
        if info.filename.startswith(HELPER_PREFIX):
            continue  # This helper was signed and stapled as a separate app bundle.
        if info.filename.endswith(".class"):
            continue  # Java class headers collide with the universal Mach-O magic.
        member_name = f"{archive_name}!/{info.filename}"
        if info.filename.lower().endswith(".jar"):
            with zipfile.ZipFile(io.BytesIO(source.read(info))) as nested:
                nested_updates = _signed_members(
                    nested, temp_directory, entitlements, identity, member_name
                )
                if nested_updates:
                    buffer = io.BytesIO()
                    with zipfile.ZipFile(buffer, "w") as destination:
                        _write_archive(nested, destination, nested_updates)
                    updates[index] = buffer.getvalue()
            continue

        with source.open(info) as member:
            magic = member.read(4)
            if magic not in MACH_O_MAGICS:
                continue
            entry_path = temp_directory / f"entry-{index}"
            entry_path.write_bytes(magic + member.read())
        if not is_mach_o(entry_path):
            continue
        try:
            subprocess.run(
                [
                    "codesign",
                    "--force",
                    "--options",
                    "runtime",
                    "--timestamp",
                    "--entitlements",
                    entitlements,
                    "--sign",
                    identity,
                    entry_path,
                ],
                check=True,
            )
            subprocess.run(
                ["codesign", "--verify", "--strict", "--all-architectures", entry_path],
                check=True,
            )
        except subprocess.CalledProcessError as error:
            raise RuntimeError(f"codesign failed for {member_name}") from error
        updates[index] = entry_path.read_bytes()
    return updates


def sign_jar(jar_path: Path, entitlements: str, identity: str) -> None:
    jar_mode = jar_path.stat().st_mode
    output_fd, output_name = tempfile.mkstemp(
        prefix=f".{jar_path.name}.", suffix=".tmp", dir=jar_path.parent
    )
    os.close(output_fd)
    output_path = Path(output_name)
    try:
        with tempfile.TemporaryDirectory(prefix="spectre-jar-sign-") as temp_directory:
            with zipfile.ZipFile(jar_path, "r") as source:
                updates = _signed_members(
                    source, Path(temp_directory), entitlements, identity, str(jar_path)
                )
                with zipfile.ZipFile(output_path, "w") as destination:
                    _write_archive(source, destination, updates)
        output_path.chmod(jar_mode)
        os.replace(output_path, jar_path)
    finally:
        output_path.unlink(missing_ok=True)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: sign-macos-shaded-jar.py JAR ENTITLEMENTS SIGNING_IDENTITY"
        )
    sign_jar(Path(sys.argv[1]), sys.argv[2], sys.argv[3])


if __name__ == "__main__":
    main()
