#!/usr/bin/env bash
set -euo pipefail
version=$1
evidence=$2
case "$version" in 0.9.0|0.10.0) ;; *) echo "unsupported version" >&2; exit 2;; esac
central=https://repo.maven.apache.org/maven2
modules=(core input-coordinator input-coordinator-server server recording recording-macos recording-linux recording-windows agent agent-runtime testing)
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
project=$root/external-consumer
repo=$root/empty-maven-repository
mkdir -p "$project/src/main/java/external" "$repo" "$evidence"
cat > "$root/settings.xml" <<EOF
<settings><mirrors><mirror><id>central-only</id><mirrorOf>*</mirrorOf><url>$central</url></mirror></mirrors><localRepository>$repo</localRepository></settings>
EOF
{
cat <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>external.probe</groupId><artifactId>fresh-consumer</artifactId><version>1</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>
EOF
for module in "${modules[@]}"; do printf '<dependency><groupId>dev.sebastiano.spectre</groupId><artifactId>spectre-%s</artifactId><version>%s</version></dependency>\n' "$module" "$version"; done
cat <<'EOF'
</dependencies><build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version><configuration><mainClass>external.Consumer</mainClass></configuration></plugin></plugins></build></project>
EOF
} > "$project/pom.xml"
cat > "$project/src/main/java/external/Consumer.java" <<'EOF'
package external;
import dev.sebastiano.spectre.recording.HostPlatform;
public final class Consumer {
  public static void main(String[] args) {
    if (!HostPlatform.INSTANCE.isLinux()) throw new AssertionError("host API did not report Linux");
    System.out.println("CENTRAL_PUBLIC_API_OK HostPlatform.isLinux=" + HostPlatform.INSTANCE.isLinux());
  }
}
EOF
command=(mvn -B -s "$root/settings.xml" -f "$project/pom.xml")
printf '$ %q ' "${command[@]}" > "$evidence/commands-and-output.txt"
printf '%s\n' 'dependency:tree compile exec:java' >> "$evidence/commands-and-output.txt"
"${command[@]}" dependency:tree compile exec:java | tee -a "$evidence/commands-and-output.txt"
python3 - "$repo" "$version" "$evidence" "${modules[@]}" <<'PY'
import hashlib,json,pathlib,sys,urllib.request,xml.etree.ElementTree as ET
repo,version,out=pathlib.Path(sys.argv[1]),sys.argv[2],pathlib.Path(sys.argv[3]); modules=sys.argv[4:]
identities=[]
for short in modules:
    artifact="spectre-"+short; base=repo/"dev/sebastiano/spectre"/artifact/version
    files={ext:base/f"{artifact}-{version}.{ext}" for ext in ("pom","jar","module")}
    # Maven consumes the POM and jar. Fetch Gradle metadata independently because Maven does not
    # request it, then require all three public coordinate declarations to agree.
    if not files["module"].is_file():
        urllib.request.urlretrieve(f"https://repo.maven.apache.org/maven2/dev/sebastiano/spectre/{artifact}/{version}/{artifact}-{version}.module", files["module"])
    if not all(path.is_file() and path.stat().st_size for path in files.values()): raise SystemExit(f"missing exact Central publication files for {artifact}")
    pom=ET.parse(files["pom"]).getroot(); ns={"m":"http://maven.apache.org/POM/4.0.0"}
    expected=("dev.sebastiano.spectre",artifact,version)
    actual=tuple(pom.findtext(f"m:{x}",namespaces=ns) for x in ("groupId","artifactId","version"))
    module=json.loads(files["module"].read_text()); component=module["component"]
    if actual != expected or (component["group"],component["module"],component["version"]) != expected: raise SystemExit(f"coordinate identity mismatch: {artifact}")
    identities.append({"gav":":".join(expected),**{ext+"Sha256":hashlib.sha256(path.read_bytes()).hexdigest() for ext,path in files.items()}})
(out/"central-module-identities.json").write_text(json.dumps(identities,indent=2)+"\n")
PY
printf '%s\n' "$central" > "$evidence/repository.txt"
