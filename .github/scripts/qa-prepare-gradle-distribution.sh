#!/usr/bin/env bash
set -euo pipefail
# This verified release carrier uses the unmodified wrapper and settings files.
test "$(git rev-parse HEAD)" = 7546c790a70abf2d9ab9322ad55a00204daa543a
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}"
if ! ./gradlew --version; then
  zip_name=gradle-9.7.1-bin.zip
  paths=("$gradle_cache/wrapper/dists/gradle-9.7.1-bin/"*/)
  [[ "${#paths[@]}" == 1 && -d ${paths[0]} ]]
  download="$RUNNER_TEMP/$zip_name"
  if ! curl -fSL --retry 5 --retry-all-errors --connect-timeout 15 --max-time 150 -o "$download" "https://downloads.gradle.org/distributions/$zip_name"; then
    gh release download v9.7.1 --repo gradle/gradle-distributions --pattern "$zip_name" --dir "$RUNNER_TEMP" --clobber
  fi
  echo "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a  $download" | sha256sum --check --strict
  cp "$download" "${paths[0]}$zip_name"
  ./gradlew --version
fi

# Seed only the precise, digest-pinned published components implicated in the previous outage.
repo="$RUNNER_TEMP/verified-foojay-repository"
marker='org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/1.0.0/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin-1.0.0'
implementation='org/gradle/toolchains/foojay-resolver/1.0.0/foojay-resolver-1.0.0'
fetch() {
  local file="$1" digest="$2"
  mkdir -p "$repo/$(dirname "$file")"
  for base in 'https://plugins.gradle.org/m2' 'https://maven.aliyun.com/repository/gradle-plugin'; do
    if curl -fsSL --retry 2 --retry-all-errors --connect-timeout 8 --max-time 30 -o "$repo/$file" "$base/$file" &&
       echo "$digest  $repo/$file" | sha256sum --check --strict; then return 0; fi
  done
  return 1
}
fetch "$marker.pom" f133249a18754ae6a1d2701d00d849b1afba7b0683ddb5fc1b18a70071cec2fa
fetch "$implementation.jar" 78b86a47dfdf7697c9bd15da78983fd80da7247d6e02fc106bdf07e0388b60a8
fetch "$implementation.pom" 9bc48b49e422d9edabc396ac18d8b041dfc22212d7fae8e35667d285d481029a
fetch "$implementation.module" 6190cf0e42e664c11e1ac0a785699ab02b549cea34392c679f36d8a2c550fcb9
mkdir -p "$gradle_cache/init.d"
cat >"$gradle_cache/init.d/qa-verified-foojay.gradle" <<GROOVY
beforeSettings { s ->
    s.pluginManagement.repositories.maven {
        name = 'VerifiedFoojayCache'
        url = new File('$repo').toURI()
        content {
            includeVersion 'org.gradle.toolchains.foojay-resolver-convention', 'org.gradle.toolchains.foojay-resolver-convention.gradle.plugin', '1.0.0'
            includeVersion 'org.gradle.toolchains', 'foojay-resolver', '1.0.0'
        }
    }
}
GROOVY
test -z "$(git status --porcelain --untracked-files=all)"
