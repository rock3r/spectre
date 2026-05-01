//! `spectre-wayland-helper` — native helper for Spectre's Linux Wayland recording path.
//!
//! Runs the `xdg-desktop-portal` ScreenCast handshake (CreateSession → SelectSources →
//! Start → OpenPipeWireRemote), gets a PipeWire FD authorised to read the granted stream
//! node, clears `O_CLOEXEC` on it, spawns `gst-launch-1.0` with the FD inherited so
//! pipewiresrc can connect to the authorised PipeWire core. The JVM side
//! ([`dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder`]) communicates with this
//! process over stdin/stdout via newline-delimited JSON.
//!
//! The previous (now-deleted) JVM-only attempt at this path went through dbus-java for the
//! D-Bus side. dbus-java's `h` (UnixFD) return-type unmarshalling turned out to be broken on
//! the GNOME mutter portal in our bake-off (#80) — the proxy received the MethodReturn but
//! couldn't extract the FD into the typed return value. zbus handles UnixFDs natively and
//! correctly; the FD-passing-into-subprocess piece is also straightforward in Rust via
//! `Command::pre_exec` + `nix::fcntl`. Both pain points the JVM side had simply don't exist
//! in this language environment.

mod portal;
mod protocol;
mod recorder;

use anyhow::Result;
use protocol::{Command, Event};
use std::io::{BufRead, Write};
use tokio::sync::mpsc;

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    // Read the start command from stdin first. The JVM writes one Start command, then waits
    // for "started" or "error" before driving any further. After the recording is in flight
    // it can write a Stop command, which we read on a separate thread (stdin can be busy).
    let start = read_start_command()?;

    // Channel for outbound events (helper → JVM). All writes go through one task to
    // serialise stdout access without juggling locks. Bounded so a runaway frame_progress
    // event source can't OOM the helper.
    let (event_tx, mut event_rx) = mpsc::channel::<Event>(32);
    let writer_task = tokio::spawn(async move {
        let stdout = tokio::io::stdout();
        let mut stdout = tokio::io::BufWriter::new(stdout);
        while let Some(event) = event_rx.recv().await {
            let line = match serde_json::to_string(&event) {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("[helper] failed to serialise event {event:?}: {e}");
                    continue;
                }
            };
            // Errors writing to stdout typically mean the JVM hung up — log and break;
            // the recorder will see EOF on its read side and abort the session.
            use tokio::io::AsyncWriteExt;
            if let Err(e) = stdout.write_all(line.as_bytes()).await {
                eprintln!("[helper] stdout write_all failed: {e}");
                break;
            }
            if let Err(e) = stdout.write_all(b"\n").await {
                eprintln!("[helper] stdout newline write failed: {e}");
                break;
            }
            if let Err(e) = stdout.flush().await {
                eprintln!("[helper] stdout flush failed: {e}");
                break;
            }
        }
    });

    // Drive the actual recording session. recorder::run owns the portal session, the
    // gst-launch subprocess, and the lifecycle; it watches stdin in parallel for the Stop
    // command and emits events through `event_tx`.
    if let Err(e) = recorder::run(start, event_tx.clone()).await {
        // recorder::run failed before it could emit its own error event — emit one here so
        // the JVM doesn't see EOF and have to guess the failure mode.
        let _ = event_tx
            .send(Event::Error {
                kind: "Helper".into(),
                message: format!("{e:#}"),
            })
            .await;
    }
    drop(event_tx); // close the channel so the writer task exits
    let _ = writer_task.await;
    Ok(())
}

/// Block on stdin until we receive the first JSON line; parse it as a [`Command::Start`].
/// Anything else is a protocol error (the JVM is supposed to send Start first; Stop on a
/// closed session is meaningless).
fn read_start_command() -> Result<protocol::StartCommand> {
    let stdin = std::io::stdin();
    let mut line = String::new();
    stdin.lock().read_line(&mut line)?;
    if line.is_empty() {
        anyhow::bail!("EOF on stdin before receiving Start command");
    }
    match serde_json::from_str::<Command>(line.trim())? {
        Command::Start(start) => Ok(start),
        Command::Stop => anyhow::bail!(
            "received Stop on stdin before any Start; helper protocol expects Start first"
        ),
    }
}
