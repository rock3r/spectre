//! JVM ↔ helper protocol DTOs.
//!
//! Newline-delimited JSON over stdin/stdout. The JVM writes one `Command::Start`, reads the
//! `Event::Started` confirmation (or `Event::Error`), then later writes a `Command::Stop` and
//! reads `Event::Stopped`. `Event::FrameProgress` is optional and may stream during recording.
//!
//! All field names are snake_case to match common JSON conventions; on the JVM side
//! kotlinx.serialization will pick those up via `@SerialName` annotations.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
pub enum Command {
    Start(StartCommand),
    Stop,
}

/// Initial parameters from the JVM. Mirrors `RecordingOptions` + the geometry needed for the
/// gst-launch pipeline. Region is in AWT screen-pixel coordinates; the helper translates to
/// stream-relative coordinates internally once the portal hands back a stream position.
#[derive(Debug, Clone, Deserialize)]
pub struct StartCommand {
    /// Subset of source types the user can pick at the portal dialog. `monitor` is the only
    /// one Spectre's region recording supports today; window-targeted capture would need
    /// `window` plus a different post-portal flow.
    pub source_types: Vec<SourceType>,
    pub cursor_mode: CursorMode,
    pub frame_rate: u32,
    pub region: Region,
    pub output: String,
    pub codec: String,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceType {
    Monitor,
    Window,
    Virtual,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CursorMode {
    /// Cursor not drawn into the captured frames.
    Hidden,
    /// Cursor pixels baked into the frames — matches `RecordingOptions.captureCursor=true`.
    Embedded,
    /// Cursor delivered as out-of-band PipeWire metadata. Spectre doesn't consume this today.
    Metadata,
}

/// Recording region in pixel coordinates.
///
/// All four fields are signed `i32` to match the JVM-side `Region` (which serialises Kotlin
/// `Int`s, themselves derived from `java.awt.Rectangle.{x,y,width,height}` — also signed).
/// Wire-format compatibility on both sides is the primary reason: serde would otherwise
/// reject a negative `width` from the JVM with a generic "invalid type: integer" rather
/// than letting our domain check in [`crate::gst::build_pipewire_argv`] surface a clear
/// "region must have positive dimensions" error.
///
/// Negative widths / heights *are* possible from `java.awt.Rectangle` (it doesn't enforce
/// non-negative even though the geometry rarely makes sense), so accepting them at the
/// boundary and validating downstream gives a better error trail than mid-deserialisation
/// rejection.
#[derive(Debug, Clone, Copy, Deserialize)]
pub struct Region {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum Event {
    /// Portal handshake completed and gst-launch is recording. Includes diagnostic info the
    /// JVM logs alongside its own bookkeeping.
    Started {
        node_id: u32,
        stream_size: [u32; 2],
        stream_position: [i32; 2],
        gst_pid: u32,
    },
    /// Optional periodic update for long recordings. The recorder lifecycle doesn't currently
    /// publish these, but the variant exists so a future stage can stream progress without a
    /// protocol bump.
    FrameProgress { frames: u64 },
    /// Recording stopped cleanly: gst-launch exited 0, the mux finalised, the file is on disk.
    Stopped { output_size_bytes: u64 },
    /// Anything that didn't reach a Stopped state. `kind` is a coarse category the JVM can
    /// pattern-match for surfacing the right exception type; `message` is the human-readable
    /// detail.
    Error { kind: String, message: String },
}
