#!/usr/bin/env bash
set -euo pipefail
version=${1:?version required}; evidence=${2:?evidence directory required}
case "$version" in 0.9.0|0.10.0) ;; *) echo "unsupported version: $version" >&2; exit 2;; esac
central=https://repo.maven.apache.org/maven2
modules=(core input-coordinator input-coordinator-server server recording recording-macos recording-linux recording-windows agent agent-runtime testing)
root=$(mktemp -d); trap 'rm -rf "$root"' EXIT
repo=$root/empty-maven-repository; project=$root/external-consumer
mkdir -p "$repo" "$project/src/main/java/external" "$evidence"
cat > "$root/settings.xml" <<XML
<settings><mirrors><mirror><id>central-only</id><mirrorOf>*</mirrorOf><url>$central</url></mirror></mirrors><localRepository>$repo</localRepository></settings>
XML
# Verify each Spectre publication without resolving its graph. A Google-only transitive must not
# make a real Central-hosted Spectre coordinate look absent.
python3 - "$repo" "$version" "$evidence" "${modules[@]}" <<'PY'
import hashlib,json,pathlib,sys,urllib.request,xml.etree.ElementTree as ET
repo,version,out=pathlib.Path(sys.argv[1]),sys.argv[2],pathlib.Path(sys.argv[3]); identities=[]
for short in sys.argv[4:]:
    artifact="spectre-"+short; expected=("dev.sebastiano.spectre",artifact,version)
    url=f"https://repo.maven.apache.org/maven2/dev/sebastiano/spectre/{artifact}/{version}"
    base=repo/"dev/sebastiano/spectre"/artifact/version; base.mkdir(parents=True)
    files={ext:base/f"{artifact}-{version}.{ext}" for ext in ("pom","jar","module")}
    for path in files.values(): urllib.request.urlretrieve(f"{url}/{path.name}",path)
    pom=ET.parse(files["pom"]).getroot(); ns={"m":"http://maven.apache.org/POM/4.0.0"}
    actual=tuple(pom.findtext(f"m:{x}",namespaces=ns) for x in ("groupId","artifactId","version"))
    component=json.loads(files["module"].read_text())["component"]
    metadata=(component["group"],component["module"],component["version"])
    if actual != expected or metadata != expected or any(not p.stat().st_size for p in files.values()):
        raise SystemExit(f"Central identity mismatch for {':'.join(expected)}")
    identities.append({"gav":":".join(expected),**{ext+"Sha256":hashlib.file_digest(path.open("rb"),"sha256").hexdigest() for ext,path in files.items()}})
(out/"central-module-identities.json").write_text(json.dumps(identities,indent=2)+"\n")
PY
cat > "$project/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>external.probe</groupId><artifactId>fresh-consumer</artifactId><version>1</version>
<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
<dependencies><dependency><groupId>dev.sebastiano.spectre</groupId><artifactId>spectre-recording</artifactId><version>$version</version></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin><plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version><configuration><mainClass>external.Consumer</mainClass></configuration></plugin></plugins></build></project>
XML
cat > "$project/src/main/java/external/Consumer.java" <<'JAVA'
package external;
import dev.sebastiano.spectre.recording.RecordingOptions;
public final class Consumer {
  public static void main(String[] args) {
    RecordingOptions options = new RecordingOptions();
    if (options.getFrameRate() != 30 || !options.getCaptureCursor()) throw new AssertionError("unexpected defaults");
    System.out.println("CENTRAL_PUBLIC_API_OK frameRate=" + options.getFrameRate() + " captureCursor=" + options.getCaptureCursor());
  }
}
JAVA
command=(mvn -B -s "$root/settings.xml" -f "$project/pom.xml")
{ printf '$'; printf ' %q' "${command[@]}"; printf ' dependency:tree compile exec:java\n'; } > "$evidence/commands-and-output.txt"
if ! "${command[@]}" dependency:tree compile exec:java 2>&1 | tee -a "$evidence/commands-and-output.txt"; then
  echo 'FAIL: genuine unmodified recording graph did not resolve from Central only; Maven output above preserves the repository-scoped failure.' > "$evidence/full-consumer-status.txt"
  exit 1
fi
echo 'PASS: genuine spectre-recording dependency graph resolved from Central only.' > "$evidence/full-consumer-status.txt"
echo "$central (exclusive consumer mirror; exact Spectre GAV files also fetched directly and nontransitively)" > "$evidence/repository-scope.txt"
