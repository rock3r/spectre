//! Compatibility check against the parser shipped in Spectre 0.9.0.
//!
//! These DTOs reproduce the released `v0.9.0` schema at commit
//! 8ff6213720307baca9fb99929d9e8376f7e2e63c. In particular, `cursor_mode` was mandatory.

use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum Command {
    Screenshot(ScreenshotCommand),
}

#[derive(Debug, Deserialize)]
struct ScreenshotCommand {
    backend: CaptureBackend,
    target: CaptureTarget,
    #[serde(default)]
    source_types: Vec<SourceType>,
    cursor_mode: CursorMode,
    region: Region,
    #[serde(default)]
    screen_size: Option<[i32; 4]>,
    output: String,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum CaptureBackend {
    WaylandPortal,
    X11,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum CaptureTarget {
    Region,
    Window,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum SourceType {
    Monitor,
    Window,
    Virtual,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum CursorMode {
    Hidden,
    Embedded,
    Metadata,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
struct Region {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

const SESSION_SCREENSHOT: &str =
    include_str!("../../../src/test/resources/protocol/v0.9/session-screenshot.json");

#[test]
fn kotlin_session_screenshot_is_accepted_by_the_released_v0_9_parser() {
    let Command::Screenshot(command) = serde_json::from_str(SESSION_SCREENSHOT).unwrap();

    assert_eq!(command.backend, CaptureBackend::WaylandPortal);
    assert_eq!(command.target, CaptureTarget::Region);
    assert_eq!(command.source_types, [SourceType::Monitor]);
    assert_eq!(command.cursor_mode, CursorMode::Embedded);
    assert_eq!(
        command.region,
        Region {
            x: -10,
            y: 20,
            width: 2,
            height: 3,
        }
    );
    assert_eq!(command.screen_size, Some([-100, 0, 100, 100]));
    assert_eq!(command.output, "OUTPUT");
}

#[test]
fn released_v0_9_parser_rejects_a_session_screenshot_without_cursor_mode() {
    let value: serde_json::Value = serde_json::from_str(SESSION_SCREENSHOT).unwrap();
    let mut object = value.as_object().unwrap().clone();
    object.remove("cursor_mode");

    let error = serde_json::from_value::<Command>(object.into()).unwrap_err();
    assert!(error.to_string().contains("cursor_mode"), "{error}");
}
