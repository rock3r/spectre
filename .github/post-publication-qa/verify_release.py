#!/usr/bin/env python3
import argparse, hashlib, json, os, pathlib, re, urllib.request

REPO = "rock3r/spectre"

def get_json(url):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "spectre-publication-probe"})
    with urllib.request.urlopen(req) as response:
        return json.load(response)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--control", required=True)
    parser.add_argument("--asset")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    values = {}
    for line in pathlib.Path(args.control).read_text().splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1); values[key] = value
    version, expected_sha = values.get("VERSION", ""), values.get("SOURCE_SHA", "")
    if version not in {"0.9.0", "0.10.0"} or not re.fullmatch(r"[0-9a-f]{40}", expected_sha):
        raise SystemExit("control must pin supported VERSION and lowercase 40-character SOURCE_SHA")
    release = get_json(f"https://api.github.com/repos/{REPO}/releases/tags/v{version}")
    if release["draft"] or release["tag_name"] != f"v{version}":
        raise SystemExit("release is absent, draft, or has the wrong tag")
    tag = get_json(f"https://api.github.com/repos/{REPO}/git/ref/tags/v{version}")["object"]
    while tag["type"] == "tag":
        tag = get_json(tag["url"])["object"]
    if tag["type"] != "commit" or tag["sha"] != expected_sha:
        raise SystemExit(f"peeled tag SHA {tag['sha']} != control {expected_sha}")
    evidence = {"version": version, "releaseUrl": release["html_url"], "draft": release["draft"], "peeledSourceSha": tag["sha"]}
    if args.asset:
        matches = [a for a in release["assets"] if a["name"] == args.asset]
        if len(matches) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", matches[0].get("digest") or ""):
            raise SystemExit("release must expose exactly one asset with a GitHub sha256 digest")
        destination = pathlib.Path(args.output).parent / args.asset
        urllib.request.urlretrieve(matches[0]["browser_download_url"], destination)
        actual = hashlib.sha256(destination.read_bytes()).hexdigest()
        if f"sha256:{actual}" != matches[0]["digest"]:
            raise SystemExit("downloaded bytes disagree with GitHub release asset digest")
        evidence["asset"] = {"name": args.asset, "apiDigest": matches[0]["digest"], "downloadSha256": actual, "size": destination.stat().st_size}
    pathlib.Path(args.output).write_text(json.dumps(evidence, indent=2) + "\n")

if __name__ == "__main__": main()
