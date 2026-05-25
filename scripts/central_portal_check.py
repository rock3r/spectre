#!/usr/bin/env python3
"""Validate Spectre Maven Central Portal deployments.

Credentials are read from the 1Password item "Spectre Maven Central Portal".
Secrets are never printed.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path


DEFAULT_ITEM = "Spectre Maven Central Portal"
DEFAULT_BASE_URL = "https://central.sonatype.com/api/v1/publisher"
GROUP_PATH = "dev/sebastiano/spectre"
EXPECTED_AGENT_CLASS = "dev.sebastiano.spectre.agent.runtime.SpectreAgent"

COMPONENTS = (
    "spectre-agent",
    "spectre-agent-runtime",
    "spectre-core",
    "spectre-recording",
    "spectre-recording-linux",
    "spectre-recording-macos",
    "spectre-recording-windows",
    "spectre-server",
    "spectre-testing",
)

ARTIFACT_SUFFIXES = (
    ".jar",
    "-sources.jar",
    "-javadoc.jar",
    ".pom",
    ".module",
)

CHECKSUM_ALGORITHMS = ("md5", "sha1", "sha256", "sha512")

MIN_MAIN_JAR_SIZES = {
    "spectre-agent": 100_000,
    "spectre-agent-runtime": 100_000,
    "spectre-core": 100_000,
    "spectre-recording": 100_000,
    "spectre-recording-linux": 100_000,
    "spectre-recording-macos": 50_000,
    "spectre-recording-windows": 1_000_000,
    "spectre-server": 50_000,
    "spectre-testing": 1_000,
}

HELPER_ENTRIES = {
    "spectre-recording-linux": (
        "native/linux/x86_64/spectre-wayland-helper",
        "native/linux/aarch64/spectre-wayland-helper",
    ),
    "spectre-recording-macos": ("native/macos/spectre-screencapture",),
    "spectre-recording-windows": (
        "native/windows/x64/spectre-window-capture.exe",
        "native/windows/arm64/spectre-window-capture.exe",
    ),
}

FORBIDDEN_AGENT_RUNTIME_PREFIXES = (
    "androidx/compose/",
    "org/jetbrains/compose/",
    "org/jetbrains/skiko/",
    "dev/sebastiano/spectre/core/",
    "kotlin/Pair.class",
    "kotlinx/coroutines/",
)


@dataclass(frozen=True)
class FileCheck:
    path: str
    size: int


class CentralPortalError(RuntimeError):
    """Raised when Central Portal validation cannot continue."""


def expected_files_for_version(version: str) -> list[str]:
    files: list[str] = []
    for relative_path in expected_payload_files_for_version(version):
        files.append(relative_path)
        files.append(f"{relative_path}.asc")
        for algorithm in CHECKSUM_ALGORITHMS:
            files.append(f"{relative_path}.{algorithm}")
            files.append(f"{relative_path}.asc.{algorithm}")
    return files


def expected_payload_files_for_version(version: str) -> list[str]:
    files: list[str] = []
    for component in COMPONENTS:
        base = f"{component}-{version}"
        prefix = f"{GROUP_PATH}/{component}/{version}/"
        for suffix in ARTIFACT_SUFFIXES:
            files.append(f"{prefix}{base}{suffix}")
    return files


def field_map(item: dict) -> dict[str, str]:
    fields: dict[str, str] = {}
    for field in item.get("fields", []):
        label = str(field.get("label") or field.get("id") or "").strip().lower()
        value = field.get("value")
        if label and value:
            fields[label] = str(value).strip()
    return fields


def bearer_token_from_item(item: dict) -> str:
    fields = field_map(item)
    username = fields.get("username")
    password = fields.get("password")
    if username and password:
        return base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")

    for key in (
        "username:password (base64)",
        "username:password base64",
        "base64",
        "bearer",
        "token",
    ):
        value = fields.get(key)
        if value:
            return value
    raise CentralPortalError(
        "1Password item must contain either username/password fields or a "
        "'username:password (base64)' field."
    )


def read_one_password_item(item_name: str) -> dict:
    if shutil.which("op") is None:
        raise CentralPortalError("1Password CLI `op` is not installed or not on PATH.")
    result = subprocess.run(
        ["op", "item", "get", item_name, "--format", "json"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise CentralPortalError(
            "Could not read 1Password item "
            f"{item_name!r}. Is `op` signed in?\n{result.stderr.strip()}"
        )
    return json.loads(result.stdout)


def bearer_token_from_environment() -> str | None:
    for key in ("MAVEN_CENTRAL_BEARER_TOKEN", "CENTRAL_PORTAL_BEARER_TOKEN"):
        value = os.environ.get(key)
        if value:
            return value.strip()

    username = os.environ.get("MAVEN_CENTRAL_USERNAME") or os.environ.get(
        "ORG_GRADLE_PROJECT_mavenCentralUsername"
    )
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD") or os.environ.get(
        "ORG_GRADLE_PROJECT_mavenCentralPassword"
    )
    if username and password:
        return base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return None


def load_bearer_token(item_name: str) -> str:
    env_token = bearer_token_from_environment()
    if env_token:
        return env_token
    return bearer_token_from_item(read_one_password_item(item_name))


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def request(
    url: str,
    token: str,
    *,
    method: str = "GET",
    body: bytes | None = None,
    content_type: str | None = None,
    output: Path | None = None,
) -> tuple[int, bytes]:
    headers = auth_headers(token)
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, method=method, headers=headers, data=body)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            size_header = response.headers.get("Content-Length")
            if output is None:
                body = response.read()
            else:
                with output.open("wb") as sink:
                    shutil.copyfileobj(response, sink)
                body = b""
            size = int(size_header) if size_header and size_header.isdigit() else len(body)
            if output is not None and not size_header:
                size = output.stat().st_size
            return size, body
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise CentralPortalError(f"{method} {url} failed with HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise CentralPortalError(f"{method} {url} failed: {error.reason}") from error


def status(base_url: str, token: str, deployment_id: str) -> dict:
    url = f"{base_url}/status?id={deployment_id}"
    _, body = request(url, token, method="POST")
    return json.loads(body.decode("utf-8"))


def browse_deployment_files(base_url: str, token: str, deployment_id: str) -> dict[str, int]:
    files: dict[str, int] = {}
    page = 0
    page_count = 1
    while page < page_count:
        payload = {
            "page": page,
            "size": 1000,
            "sortField": "createdTimestamp",
            "sortDirection": "asc",
            "deploymentIds": [deployment_id],
            "pathStarting": f"{GROUP_PATH}/",
        }
        _, body = request(
            f"{base_url}/deployments/files",
            token,
            method="POST",
            body=json.dumps(payload).encode("utf-8"),
            content_type="application/json",
        )
        response = json.loads(body.decode("utf-8"))
        page_count = int(response.get("pageCount") or 1)
        for deployment in response.get("deployments") or []:
            for file_entry in deployment.get("deploymentFiles") or []:
                relative_path = file_entry.get("relativePath")
                size = file_entry.get("fileSize")
                if relative_path and size is not None:
                    files[str(relative_path)] = int(size)
        page += 1
    return files


def deployment_file_url(base_url: str, deployment_id: str, relative_path: str) -> str:
    return f"{base_url}/deployment/{deployment_id}/download/{relative_path}"


def download_file(
    base_url: str,
    token: str,
    deployment_id: str,
    relative_path: str,
    destination: Path,
) -> FileCheck:
    url = deployment_file_url(base_url, deployment_id, relative_path)
    size, _ = request(url, token, method="GET", output=destination)
    return FileCheck(relative_path, size)


def download_to_path(
    base_url: str,
    token: str,
    deployment_id: str,
    relative_path: str,
    root: Path,
) -> FileCheck:
    destination = root / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    return download_file(base_url, token, deployment_id, relative_path, destination)


def digest_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_checksum(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip().split()[0].lower()


def validate_jar(component: str, jar_path: Path) -> list[str]:
    errors: list[str] = []
    with zipfile.ZipFile(jar_path) as jar:
        entries = {entry.filename: entry.file_size for entry in jar.infolist()}

        if component == "spectre-recording":
            native_entries = sorted(name for name in entries if name.startswith("native/"))
            if native_entries:
                errors.append(
                    "spectre-recording API jar must not contain native entries: "
                    + ", ".join(native_entries)
                )

        for helper in HELPER_ENTRIES.get(component, ()):
            if helper not in entries:
                errors.append(f"{component} is missing helper entry {helper}")
            elif entries[helper] <= 0:
                errors.append(f"{component} helper entry {helper} is empty")

        if component == "spectre-agent":
            spike_entries = sorted(
                name for name in entries if name.startswith("dev/sebastiano/spectre/agent/spike/")
            )
            if spike_entries:
                errors.append("spectre-agent must not contain AttachSpike entries")

        if component == "spectre-agent-runtime":
            manifest = _read_manifest(jar)
            for key in ("Agent-Class", "Premain-Class"):
                if manifest.get(key) != EXPECTED_AGENT_CLASS:
                    errors.append(
                        f"spectre-agent-runtime manifest {key}={manifest.get(key)!r}, "
                        f"expected {EXPECTED_AGENT_CLASS!r}"
                    )
            leaks = sorted(
                name
                for name in entries
                if any(name.startswith(prefix) for prefix in FORBIDDEN_AGENT_RUNTIME_PREFIXES)
            )
            if leaks:
                errors.append("spectre-agent-runtime contains forbidden classes: " + ", ".join(leaks))

    return errors


def _read_manifest(jar: zipfile.ZipFile) -> dict[str, str]:
    try:
        raw = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    except KeyError:
        return {}
    attributes: dict[str, str] = {}
    current_key: str | None = None
    for line in raw.splitlines():
        if line.startswith(" ") and current_key:
            attributes[current_key] += line[1:]
        elif ":" in line:
            key, value = line.split(":", 1)
            current_key = key
            attributes[key] = value.strip()
    return attributes


def validate_deployment(base_url: str, token: str, deployment_id: str, version: str) -> int:
    deployment = status(base_url, token, deployment_id)
    errors: list[str] = []
    state = deployment.get("deploymentState")
    if state not in {"VALIDATED", "PUBLISHING", "PUBLISHED"}:
        errors.append(f"deployment state is {state!r}, expected VALIDATED/PUBLISHING/PUBLISHED")

    purls = set(deployment.get("purls") or [])
    expected_purls = {f"pkg:maven/dev.sebastiano.spectre/{component}@{version}" for component in COMPONENTS}
    missing_purls = sorted(expected_purls - purls)
    if missing_purls:
        errors.append("missing expected components: " + ", ".join(missing_purls))

    print(f"Deployment {deployment_id}: {deployment.get('deploymentName')} [{state}]")
    print()

    remote_files = browse_deployment_files(base_url, token, deployment_id)
    expected_file_set = set(expected_files_for_version(version))
    remote_file_set = set(remote_files)
    missing_files = sorted(expected_file_set - remote_file_set)
    unexpected_files = sorted(remote_file_set - expected_file_set)
    if missing_files:
        errors.append("missing expected files: " + ", ".join(missing_files))
    if unexpected_files:
        errors.append("unexpected files in deployment: " + ", ".join(unexpected_files))

    checked: list[FileCheck] = []
    expected_files = expected_files_for_version(version)
    total_files = len(expected_files)
    with tempfile.TemporaryDirectory(prefix="spectre-central-") as tmp:
        tmp_path = Path(tmp)
        for index, relative_path in enumerate(expected_files, start=1):
            print(f"[{index:02d}/{total_files}] {relative_path}", flush=True)
            file_check = download_to_path(base_url, token, deployment_id, relative_path, tmp_path)
            listed_size = remote_files.get(relative_path)
            if listed_size is not None and listed_size != file_check.size:
                errors.append(
                    f"{relative_path} downloaded as {file_check.size} bytes, "
                    f"but Central file listing reports {listed_size} bytes"
                )
            checked.append(file_check)

        for relative_path in expected_payload_files_for_version(version):
            payload = tmp_path / relative_path
            signature = tmp_path / f"{relative_path}.asc"
            for signed_path in (payload, signature):
                for algorithm in CHECKSUM_ALGORITHMS:
                    checksum_path = tmp_path / f"{signed_path.relative_to(tmp_path)}.{algorithm}"
                    actual = digest_file(signed_path, algorithm)
                    expected = read_checksum(checksum_path)
                    if actual != expected:
                        errors.append(
                            f"{checksum_path.relative_to(tmp_path)} is {expected}, "
                            f"but {signed_path.relative_to(tmp_path)} hashes to {actual}"
                        )

            if relative_path.endswith(".jar") and not relative_path.endswith(("-sources.jar", "-javadoc.jar")):
                component = Path(relative_path).parent.parent.name
                errors.extend(validate_jar(component, payload))
                min_size = MIN_MAIN_JAR_SIZES.get(component)
                size = payload.stat().st_size
                if min_size and size < min_size:
                    errors.append(
                        f"{Path(relative_path).name} is unexpectedly small: "
                        f"{size} bytes < {min_size} bytes"
                    )

    for file_check in checked:
        print(f"{file_check.size:>10}  {file_check.path}")

    if errors:
        print("\nValidation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"\nValidated {len(checked)} deployment files for Spectre {version}.")
    return 0


def print_status(base_url: str, token: str, deployment_id: str) -> int:
    deployment = status(base_url, token, deployment_id)
    print(json.dumps(deployment, indent=2, sort_keys=True))
    return 0


def print_files(base_url: str, token: str, deployment_id: str, version: str) -> int:
    remote_files = browse_deployment_files(base_url, token, deployment_id)
    for relative_path, size in sorted(remote_files.items()):
        print(f"{size:>10}  {relative_path}")

    expected_file_set = set(expected_files_for_version(version))
    remote_file_set = set(remote_files)
    missing_files = sorted(expected_file_set - remote_file_set)
    unexpected_files = sorted(remote_file_set - expected_file_set)
    if missing_files or unexpected_files:
        print("\nFile listing differs from expected Spectre release shape:", file=sys.stderr)
        for relative_path in missing_files:
            print(f"  - missing: {relative_path}", file=sys.stderr)
        for relative_path in unexpected_files:
            print(f"  - unexpected: {relative_path}", file=sys.stderr)
        return 1

    print(f"\n{len(remote_files)} files listed by Central.")
    return 0


def publish_deployment(base_url: str, token: str, deployment_id: str, version: str, yes: bool) -> int:
    expected = f"publish {deployment_id} {version}"
    if not yes:
        print("This permanently publishes the validated Central Portal deployment.")
        print(f"Type exactly: {expected}")
        typed = input("> ").strip()
        if typed != expected:
            print("Confirmation did not match; not publishing.")
            return 2
    url = f"{base_url}/deployment/{deployment_id}"
    request(url, token, method="POST")
    print(f"Publish requested for deployment {deployment_id}.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--item", default=DEFAULT_ITEM, help="1Password item name")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Central Publisher API base URL")
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name in ("status", "files", "validate", "publish"):
        command = subparsers.add_parser(name)
        command.add_argument("--deployment-id", required=True)
        if name in {"files", "validate", "publish"}:
            command.add_argument("--version", required=True)
        if name == "publish":
            command.add_argument(
                "--yes",
                action="store_true",
                help="Skip typed confirmation. Intended for carefully reviewed automation only.",
            )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        token = load_bearer_token(args.item)
        if args.command == "status":
            return print_status(args.base_url, token, args.deployment_id)
        if args.command == "files":
            return print_files(args.base_url, token, args.deployment_id, args.version)
        if args.command == "validate":
            return validate_deployment(args.base_url, token, args.deployment_id, args.version)
        if args.command == "publish":
            return publish_deployment(
                args.base_url,
                token,
                args.deployment_id,
                args.version,
                args.yes,
            )
    except CentralPortalError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
