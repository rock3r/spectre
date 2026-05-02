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
    // Set up the writer thread FIRST so any error — including a malformed Start command on
    // stdin — surfaces as an Event::Error to the JVM instead of an unceremonious exit code
    // the JVM has to guess at. The protocol's contract is "events over stdout, terminal
    // event signals end-of-recording"; we honour it even for early-protocol errors.
    let (event_tx, event_rx) = mpsc::channel::<Event>();
    let writer_handle = thread::Builder::new()
        .name("event-writer".into())
        .spawn(move || writer_loop(event_rx))?;

    // Read the Start command. If parsing fails (EOF, malformed JSON, Stop-without-Start),
    // emit a Protocol error event and exit cleanly via the writer thread.
    let outcome: Result<()> = match read_start_command() {
        Ok(start) => recorder::run(start, event_tx.clone()),
        Err(e) => {
            let _ = event_tx.send(Event::Error {
                kind: "Protocol".into(),
                message: format!("{e:#}"),
            });
            Ok(()) // Error already reported via the event channel — don't double-report.
        }
    };

    // recorder::run can fail without having reported the error itself (e.g. portal
    // handshake exception bubbles up). Cover that case too.
    if let Err(e) = outcome {
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
