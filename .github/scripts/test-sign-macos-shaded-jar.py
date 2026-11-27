#!/usr/bin/env python3

import io
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


def make_jar(entries: list[tuple[str, bytes]]) -> bytes:
    jar_bytes = io.BytesIO()
    with zipfile.ZipFile(jar_bytes, "w") as archive:
        for name, content in entries:
            archive.writestr(name, content)
    return jar_bytes.getvalue()


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    jar = root / "cli.jar"
    tools = root / "tools"
    tools.mkdir()
    signed_log = root / "signed.log"

    darwin_entries = [
        ("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", b"\xfe\xed\xfa\xcfARM"),
        ("com/sun/jna/darwin-x86-64/libjnidispatch.jnilib", b"\xfe\xed\xfa\xcfX64"),
    ]
    linux_entry = ("com/sun/jna/linux-x86-64/libjnidispatch.so", b"\x7fELFLINUX")
    inject_entry = (
        "META-INF/spectre/inject-runtime.jar",
        make_jar([("spectre/inject.class", b"\xca\xfe\xba\xbeINJECT")]),
    )
    agent_runtime_entry = (
        "spectre/agent-runtime.jar",
        make_jar([*darwin_entries, linux_entry, inject_entry]),
    )
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
        agent_runtime_entry,
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
data = path.read_bytes()
if sys.argv[1] == '--verify':
    assert '--strict' in sys.argv
    assert '--all-architectures' in sys.argv
    assert data.endswith(b'-SIGNED')
    operation = 'verified'
else:
    assert '--timestamp' in sys.argv
    identity_index = sys.argv.index('--sign') + 1
    assert sys.argv[identity_index] == 'Developer ID'
    path.write_bytes(data + b'-SIGNED')
    operation = 'signed'
with open(os.environ['SIGNED_LOG'], 'a') as log:
    log.write(f'{operation} {data[4:].removesuffix(b"-SIGNED").decode()}\\n')
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
        for name, content in entries[2:-1]:
            assert archive.read(name) == content
        with zipfile.ZipFile(io.BytesIO(archive.read(agent_runtime_entry[0]))) as agent:
            for name, content in darwin_entries:
                assert agent.read(name) == content + b"-SIGNED", f"{name} was not signed"
            for name, content in (linux_entry, inject_entry):
                assert agent.read(name) == content

    assert signed_log.read_text().splitlines() == [
        "signed OTHER",
        "verified OTHER",
        "signed ARM",
        "verified ARM",
        "signed X64",
        "verified X64",
    ]
