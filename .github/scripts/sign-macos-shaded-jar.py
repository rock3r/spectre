#!/usr/bin/env python3
"""Sign Mach-O members of the shaded CLI jar without extracting the whole archive."""

from __future__ import annotations

import copy
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


def sign_jar(jar_path: Path, entitlements: str, identity: str) -> None:
    jar_mode = jar_path.stat().st_mode
    output_fd, output_name = tempfile.mkstemp(
        prefix=f".{jar_path.name}.", suffix=".tmp", dir=jar_path.parent
    )
    os.close(output_fd)
    output_path = Path(output_name)
    try:
        with tempfile.TemporaryDirectory(prefix="spectre-jar-sign-") as temp_directory:
            with zipfile.ZipFile(jar_path, "r") as source, zipfile.ZipFile(
                output_path, "w"
            ) as destination:
                destination.comment = source.comment
                for index, original_info in enumerate(source.infolist()):
                    content = source.read(original_info)
                    if (
                        not original_info.filename.startswith(HELPER_PREFIX)
                        and content[:4] in MACH_O_MAGICS
                    ):
                        entry_path = Path(temp_directory) / f"entry-{index}"
                        entry_path.write_bytes(content)
                        if is_mach_o(entry_path):
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
                            content = entry_path.read_bytes()

                    destination.writestr(copy.copy(original_info), content)
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
