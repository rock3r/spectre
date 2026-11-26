use serde_json::Value;
use std::io::Write;
use std::process::{Command, Stdio};

#[test]
fn stale_pid_emits_one_actionable_terminal_error() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_spectre-wayland-helper"))
        .env_remove("DISPLAY")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("start helper");
    child
        .stdin
        .take()
        .expect("helper stdin")
        .write_all(
            concat!(
                r#"{"command":"locate_window","owner_pid":4294967295,"#,
                r#""frame":{"x":1,"y":2,"width":3,"height":4}}"#,
                "\n",
            )
            .as_bytes(),
        )
        .expect("send locate_window");

    let output = child.wait_with_output().expect("wait for helper");
    assert!(
        output.status.success(),
        "helper must report typed failures: {output:?}"
    );
    let lines: Vec<_> = String::from_utf8(output.stdout)
        .expect("UTF-8 stdout")
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).expect("JSON event"))
        .collect();

    assert_eq!(1, lines.len(), "exactly one terminal event: {lines:?}");
    assert_eq!(Some("error"), lines[0]["event"].as_str());
    assert_eq!(Some("Helper"), lines[0]["kind"].as_str());
    let message = lines[0]["message"].as_str().expect("error message");
    assert!(!message.is_empty(), "original cause must not be lost");
    assert!(
        message.contains("DISPLAY") || message.contains("X11") || message.contains("display"),
        "original X11 cause must remain actionable: {message}"
    );
}
