//! Lifecycle orchestration: open a portal session, spawn `gst-launch-1.0` with the FD
//! inherited, watch stdin for Stop while the encoder runs, finalise and report stats.
//!
//! Implementation lands in the next commit.

use crate::protocol::{Event, StartCommand};
use anyhow::Result;
use tokio::sync::mpsc;

pub async fn run(_start: StartCommand, events: mpsc::Sender<Event>) -> Result<()> {
    // Placeholder so cargo check / cargo build pass while we wire up the real implementation.
    // Emit a deterministic Error event so a JVM-side smoke against this skeleton sees the
    // not-yet-implemented state explicitly rather than EOF.
    events
        .send(Event::Error {
            kind: "NotImplemented".into(),
            message: "recorder::run is a skeleton; portal + gst-launch wiring lands next."
                .into(),
        })
        .await
        .ok();
    Ok(())
}
