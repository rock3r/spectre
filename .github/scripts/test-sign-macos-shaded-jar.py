#!/usr/bin/env python3

import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import zipfile


repo_root = Path(__file__).resolve().parents[2]
signer = repo_root / ".github/scripts/sign-macos-shaded-jar.py"


def write_executable(path: Path, body: str) -> None:
    path.write_text(body)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    jar = root / "cli.jar"
    tools = root / "tools"
    tools.mkdir()
    signed_log = root / "signed.log"

    entries = [
        (
            "native/macos/SpectreCaptureHelper.app/Contents/MacOS/helper",
            b"\xfe\xed\xfa\xcfHELPER",
        ),
        ("native/macos/libother.dylib", b"\xfe\xed\xfa\xcfOTHER"),
        ("pkg/Example.class", b"\xca\xfe\xba\xbeJAVA-CLASS"),
        ("pkg/DebugProbesKt.bin", b"\xca\xfe\xba\xbeKOTLIN-BIN"),
        ("Case/Name.txt", b"upper"),
        ("case/name.txt", b"lower"),
    ]
    with zipfile.ZipFile(jar, "w") as archive:
        archive.comment = b"archive-comment"
        for index, (name, content) in enumerate(entries):
            info = zipfile.ZipInfo(name, (2020, 1, 2, 3, 4, 6 + index * 2))
            info.comment = f"comment-{index}".encode()
            info.external_attr = (0o100600 + index) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)

    with zipfile.ZipFile(jar) as archive:
        original_comment = archive.comment
        original_info = [
            (item.filename, item.date_time, item.comment, item.external_attr)
            for item in archive.infolist()
        ]

    write_executable(
        tools / "file",
        """#!/usr/bin/env python3
import pathlib, sys
data = pathlib.Path(sys.argv[-1]).read_bytes()
print('Mach-O 64-bit binary' if data.startswith(b'\\xfe\\xed\\xfa\\xcf') else 'compiled Java class data')
""",
    )
    write_executable(
        tools / "codesign",
        """#!/usr/bin/env python3
import os, pathlib, sys
path = pathlib.Path(sys.argv[-1])
with open(os.environ['SIGNED_LOG'], 'a') as log:
    log.write(path.name + '\\n')
path.write_bytes(path.read_bytes() + b'-SIGNED')
""",
    )

    environment = os.environ.copy()
    environment["PATH"] = f"{tools}:{environment['PATH']}"
    environment["SIGNED_LOG"] = str(signed_log)
    subprocess.run(
        [sys.executable, str(signer), str(jar), "entitlements.plist", "Developer ID"],
        check=True,
        env=environment,
    )

    with zipfile.ZipFile(jar) as archive:
        assert archive.comment == original_comment
        actual_info = [
            (item.filename, item.date_time, item.comment, item.external_attr)
            for item in archive.infolist()
        ]
        assert actual_info == original_info
        assert archive.read(entries[0][0]) == entries[0][1]
        assert archive.read(entries[1][0]) == entries[1][1] + b"-SIGNED"
        for name, content in entries[2:]:
            assert archive.read(name) == content

    assert signed_log.read_text().splitlines() == ["entry-1"]
