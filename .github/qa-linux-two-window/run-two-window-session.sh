#!/usr/bin/env bash
set -Eeuo pipefail
out=$TWO_WINDOW_EVIDENCE
mkdir -p "$out"
export DISPLAY=:99
Xvfb "$DISPLAY" -screen 0 1920x1080x24 -nolisten tcp >"$out/xvfb.log" 2>&1 &
xvfb_pid=$!
openbox >"$out/openbox.log" 2>&1 &
wm_pid=$!
cleanup() {
  kill "$wm_pid" "$xvfb_pid" 2>/dev/null || true
  wait "$wm_pid" "$xvfb_pid" 2>/dev/null || true
}
trap cleanup EXIT
for _ in {1..100}; do
  xdpyinfo >/dev/null 2>&1 && xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && break
  sleep .1
done
xdpyinfo | grep dimensions | tee "$out/display.txt"
xprop -root _NET_SUPPORTING_WM_CHECK | tee "$out/window-manager.txt"
gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
  --method org.freedesktop.DBus.Properties.Set org.a11y.Status IsEnabled '<true>' | tee "$out/a11y-enabled.txt"
test "$(gdbus call --session --dest org.a11y.Bus --object-path /org/a11y/bus \
  --method org.freedesktop.DBus.Properties.Get org.a11y.Status IsEnabled)" = '(<true>,)'

fixture_home="$RUNNER_TEMP/two-window-fixture-home"
mkdir -p "$fixture_home"
printf '%s\n' 'assistive_technologies=org.GNOME.Accessibility.AtkWrapper' > "$fixture_home/.accessibility.properties"
bridge=/usr/share/java/java-atk-wrapper.jar
test -f "$bridge"
export FIXTURE_HOME="$fixture_home"
export FIXTURE_JAVA_TOOL_OPTIONS="-Djavax.accessibility.assistive_technologies=org.GNOME.Accessibility.AtkWrapper -Djava.library.path=/usr/lib/x86_64-linux-gnu/jni -Dskiko.renderApi=SOFTWARE_COMPAT"
export LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu/jni${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

./gradlew --no-configuration-cache --no-daemon -I "$QA_DIR/write-attached-validation-classpath.gradle" \
  :sample-desktop:writeAttachedValidationClasspath --console=plain
classpath=sample-desktop/build/two-window-qa-classpath.txt
printf '%s' "$bridge:$(cat "$classpath")" > "$classpath"
python3 -u "$QA_DIR/verify-two-window-package.py" "$PWD" \
  > >(tee "$out/verify.stdout.log") 2> >(tee "$out/verify.stderr.log" >&2)
