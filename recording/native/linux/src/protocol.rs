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
    Screenshot(ScreenshotCommand),
    Stop,
    PointerMove { x: i32, y: i32 },
    PointerButton { button: i32, pressed: bool },
    Key { key_code: i32, pressed: bool },
    PointerAxis { axis: u32, steps: i32 },
    /// Session only. Bind a GNOME Mutter window stream to the window that has keyboard focus now,
    /// keep it under `key`, and write its first frame to `output` as an RGBA PNG. The JVM focuses
    /// the window first and checks the frame before it trusts the binding.
    BindFocusedWindow {
        key: String,
        output: String,
        /// Mutter fixes the cursor mode when it binds the stream.
        #[serde(default = "hidden_cursor")]
        cursor_mode: CursorMode,
    },
    /// Session only. Write one RGBA PNG frame of the window bound under `key` to `output`.
    WindowScreenshot { key: String, output: String },
    /// Session only. Record the window bound under `key`, cropped to `region` in stream pixels.
    StartWindowRecording(WindowRecordingCommand),
    /// Session only. Stop the Mutter stream bound under `key`.
    UnbindWindow { key: String },
    /// Session only. Ask the window manager to activate the Xwayland client window whose
    /// root-relative geometry is `frame` (device pixels, client area), owned by `owner_pid`.
    /// GNOME ignores a Java window's own focus requests once another window holds focus; the
    /// JVM sends this when `toFront`/`requestFocus` did not give the window focus.
    ActivateWindow {
        #[serde(default)]
        display_name: Option<String>,
        frame: Region,
        #[serde(default)]
        owner_pid: Option<u32>,
    },
}

/// Recording of a bound Mutter window stream. `region` and `stream_size` are in stream pixels:
/// the JVM measures both on the frame it got when it bound the window.
#[derive(Debug, Clone, Deserialize)]
pub struct WindowRecordingCommand {
    pub key: String,
    pub region: Region,
    pub stream_size: [u32; 2],
    pub frame_rate: u32,
    pub output: String,
    pub codec: String,
}

/// Initial parameters from the JVM. Mirrors `RecordingOptions` + the geometry needed for the
/// gst-launch pipeline. Region is in AWT screen-pixel coordinates; the helper translates to
/// stream-relative coordinates internally once the portal hands back a stream position.
#[derive(Debug, Clone, Deserialize)]
pub struct StartCommand {
    #[serde(default = "default_capture_backend")]
    pub backend: CaptureBackend,
    #[serde(default = "default_capture_target")]
    pub target: CaptureTarget,
    /// Subset of source types the user can pick at the portal dialog. `monitor` is the only
    /// one Spectre's region recording supports today; window-targeted capture would need
    /// `window` plus a different post-portal flow.
    #[serde(default)]
    pub source_types: Vec<SourceType>,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub window_title: Option<String>,
    pub cursor_mode: CursorMode,
    pub frame_rate: u32,
    pub region: Region,
    /// AWT bounds of the display that contains [region], when known.
    /// Origin + size in the same pixel space as [region]. Used to convert mixed
    /// HiDPI AWT/XWayland coordinates onto the selected portal stream — never the
    /// primary `Toolkit.screenSize`, which is wrong for a secondary monitor.
    #[serde(default)]
    pub screen_size: Option<[i32; 4]>,
    pub output: String,
    pub codec: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ScreenshotCommand {
    pub backend: CaptureBackend,
    pub target: CaptureTarget,
    #[serde(default)]
    pub source_types: Vec<SourceType>,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub window_title: Option<String>,
    /// Selector for an untitled window (a `JWindow` or plain AWT `Window`): its frame in X11
    /// root coordinates, device pixels. X11 window target only; mutually exclusive with
    /// [window_title].
    #[serde(default)]
    pub window_frame: Option<Region>,
    /// Process that owns the target window. The frame selector skips windows whose
    /// `_NET_WM_PID` names another process.
    #[serde(default)]
    pub window_owner_pid: Option<u32>,
    pub cursor_mode: CursorMode,
    pub region: Region,
    /// AWT bounds of the display that contains [region], when known (`x,y,w,h`).
    #[serde(default)]
    pub screen_size: Option<[i32; 4]>,
    pub output: String,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CaptureBackend {
    WaylandPortal,
    X11,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CaptureTarget {
    Region,
    Window,
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
#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
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
    /// One-shot screenshot command completed and wrote a readable image file.
    ScreenshotSaved { output_size_bytes: u64 },
    /// Pointer/key command applied on the held RemoteDesktop session.
    InputAck,
    /// A Mutter window stream is bound under `key`. `logical_size` is the stream's `size`
    /// parameter: on GNOME 50 the size of the monitor, not of the window.
    WindowBound {
        key: String,
        node_id: u32,
        logical_size: [i32; 2],
    },
    /// The Mutter stream under `key` is stopped (`was_bound` false when nothing was bound).
    WindowUnbound { key: String, was_bound: bool },
    /// The window manager was asked to activate the X11 window `xid`. The JVM still confirms
    /// focus itself: the request can be refused.
    WindowActivated { xid: u64 },
    /// Anything that didn't reach a Stopped state. `kind` is a coarse category the JVM can
    /// pattern-match for surfacing the right exception type; `message` is the human-readable
    /// detail.
    Error { kind: String, message: String },
}

fn default_capture_backend() -> CaptureBackend {
    CaptureBackend::WaylandPortal
}

fn default_capture_target() -> CaptureTarget {
    CaptureTarget::Region
}

fn hidden_cursor() -> CursorMode {
    CursorMode::Hidden
}
