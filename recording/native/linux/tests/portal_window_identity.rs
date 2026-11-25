use serde_json::json;
use std::io::Write;
use std::process::{Command, Stdio};

#[test]
fn window_requests_fail_before_portal_or_output_access() {
    let output = std::env::temp_dir().join(format!("spectre-window-{}", std::process::id()));
    assert!(!output.exists());
    for command in ["start", "screenshot"] {
        for (target, sources, selector) in [
            ("window", json!(["monitor"]), json!({})),
            ("region", json!(["monitor", "window"]), json!({})),
            ("region", json!(["monitor"]), json!({"window_title": "Shared title"})),
            ("region", json!(["monitor"]), json!({"window_owner_pid": 42})),
        ] {
            let mut request = json!({
                "command": command, "target": target, "backend": "wayland_portal",
                "source_types": sources, "cursor_mode": "embedded", "output": output,
                "region": {"x": 10, "y": 20, "width": 300, "height": 200},
                "codec": "libx264", "frame_rate": 30
            });
            request.as_object_mut().unwrap().extend(selector.as_object().unwrap().clone());
            let mut child = Command::new(env!("CARGO_BIN_EXE_spectre-wayland-helper"))
                .env("DBUS_SESSION_BUS_ADDRESS", "unix:path=/nonexistent")
                .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
            writeln!(child.stdin.take().unwrap(), "{}", request).unwrap();
            let result = child.wait_with_output().unwrap();
            let stdout = String::from_utf8(result.stdout).unwrap();
            assert!(stdout.contains("cannot verify"), "{request}: {stdout}");
            assert!(!output.exists());
        }
    }
}
