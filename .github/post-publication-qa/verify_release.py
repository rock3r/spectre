#!/usr/bin/env python3
import argparse, hashlib, json, os, pathlib, re, sys, time, urllib.error, urllib.parse, urllib.request

REPO = "rock3r/spectre"

API_ORIGIN = "https://api.github.com"
API_PATH = re.compile(r"/repos/rock3r/spectre/(?:releases/tags/v(?:0\.9\.0|0\.10\.0)|git/ref/tags/v(?:0\.9\.0|0\.10\.0)|git/tags/[0-9a-f]{40})")

def get_json(url, responses):
    parsed = urllib.parse.urlsplit(url)
    if f"{parsed.scheme}://{parsed.netloc}" != API_ORIGIN or not API_PATH.fullmatch(parsed.path) or parsed.query or parsed.fragment:
        raise SystemExit(f"refusing untrusted GitHub API URL: {url}")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise SystemExit("GITHUB_TOKEN read-only Actions token is required for GitHub API metadata")
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}", "User-Agent": "spectre-publication-probe", "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            responses.append({"url": url, "status": response.status})
            print(f"GitHub API {response.status}: {url}", file=sys.stderr)
            if response.status != 200: raise SystemExit(f"unexpected GitHub API response {response.status}: {url}")
            return json.load(response)
    except urllib.error.HTTPError as error:
        responses.append({"url": url, "status": error.code})
        print(f"GitHub API failure {error.code}: {url}: {error.reason}", file=sys.stderr)
        raise

def download_public(url, destination, attempts=3):
    # Deliberately no Authorization header: release bytes and CDN redirects remain public.
    request = urllib.request.Request(url, headers={"User-Agent": "spectre-publication-probe"})
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
                if response.status != 200: raise urllib.error.HTTPError(url, response.status, "unexpected download status", response.headers, None)
                digest = hashlib.sha256()
                while chunk := response.read(1024 * 1024): digest.update(chunk); output.write(chunk)
                print(f"public asset download HTTP {response.status} (attempt {attempt}/{attempts}): {url}", file=sys.stderr)
                return digest.hexdigest()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            destination.unlink(missing_ok=True)
            code = getattr(error, "code", "network")
            print(f"public asset download failure HTTP {code} (attempt {attempt}/{attempts}): {url}: {error}", file=sys.stderr)
            transient = code == "network" or code == 408 or code == 429 or isinstance(code, int) and 500 <= code < 600
            if attempt == attempts or not transient: raise
            time.sleep(attempt * 2)

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
    responses = []
    release = get_json(f"{API_ORIGIN}/repos/{REPO}/releases/tags/v{version}", responses)
    if release["draft"] or release["tag_name"] != f"v{version}":
        raise SystemExit("release is absent, draft, or has the wrong tag")
    tag = get_json(f"{API_ORIGIN}/repos/{REPO}/git/ref/tags/v{version}", responses)["object"]
    while tag["type"] == "tag":
        tag = get_json(tag["url"], responses)["object"]
    if tag["type"] != "commit" or tag["sha"] != expected_sha:
        raise SystemExit(f"peeled tag SHA {tag['sha']} != control {expected_sha}")
    evidence = {"version": version, "releaseUrl": release["html_url"], "draft": release["draft"], "peeledSourceSha": tag["sha"], "apiResponses": responses}
    if args.asset:
        matches = [a for a in release["assets"] if a["name"] == args.asset]
        if len(matches) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", matches[0].get("digest") or ""):
            raise SystemExit("release must expose exactly one asset with a GitHub sha256 digest")
        destination = pathlib.Path(args.output).parent / args.asset
        actual = download_public(matches[0]["browser_download_url"], destination)
        if f"sha256:{actual}" != matches[0]["digest"]:
            raise SystemExit("downloaded bytes disagree with GitHub release asset digest")
        evidence["asset"] = {"name": args.asset, "apiDigest": matches[0]["digest"], "downloadSha256": actual, "size": destination.stat().st_size}
    pathlib.Path(args.output).write_text(json.dumps(evidence, indent=2) + "\n")

if __name__ == "__main__": main()
