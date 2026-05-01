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

mod gst;
mod portal;
mod protocol;
mod recorder;

use anyhow::Result;
use protocol::{Command, Event};
use std::io::BufRead;
use std::sync::mpsc;
use std::thread;

fn main() -> Result<()> {
    // Read the start command from stdin first. The JVM writes one Start command, then waits
    // for "started" or "error" before driving any further. After the recording is in flight
    // it can write a Stop command, which we read on a separate thread (stdin can be busy).
    let start = read_start_command()?;

    // Channel for outbound events (helper → JVM). All writes go through one thread to
    // serialise stdout access without juggling locks. Bounded depth via the receiver-side
    // logic; producers don't queue more than one event before flushing.
    let (event_tx, event_rx) = mpsc::channel::<Event>();
    let writer_handle = thread::Builder::new()
        .name("event-writer".into())
        .spawn(move || writer_loop(event_rx))?;

    // Drive the actual recording session. recorder::run owns the portal session, the
    // gst-launch subprocess, and the lifecycle; it watches stdin in parallel for the Stop
    // command and emits events through `event_tx`.
    if let Err(e) = recorder::run(start, event_tx.clone()) {
        // recorder::run failed before it could emit its own error event — emit one here so
        // the JVM doesn't see EOF and have to guess the failure mode.
        let _ = event_tx.send(Event::Error {
            kind: "Helper".into(),
            message: format!("{e:#}"),
        });
    }
    drop(event_tx); // close the channel so the writer thread exits
    let _ = writer_handle.join();
    Ok(())
}

/// Drain [`Event`]s from `rx` and write each as one newline-terminated JSON line to stdout.
/// Stops on channel close (all senders dropped) or on the first stdout write error — typically
/// the JVM hanging up.
fn writer_loop(rx: mpsc::Receiver<Event>) {
    use std::io::Write;
    let stdout = std::io::stdout();
    let mut stdout = stdout.lock();
    while let Ok(event) = rx.recv() {
        let line = match serde_json::to_string(&event) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[helper] failed to serialise event {event:?}: {e}");
                continue;
            }
        };
        if let Err(e) = stdout.write_all(line.as_bytes()) {
            eprintln!("[helper] stdout write failed: {e}");
            break;
        }
        if let Err(e) = stdout.write_all(b"\n") {
            eprintln!("[helper] stdout newline write failed: {e}");
            break;
        }
        if let Err(e) = stdout.flush() {
            eprintln!("[helper] stdout flush failed: {e}");
            break;
        }
    }
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
