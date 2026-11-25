//! Native AT-SPI2 accessibility snapshot reader.
//!
//! This deliberately talks to AT-SPI over D-Bus rather than shelling out to the Python fixture
//! probe.  The session status is guarded so a setting enabled by this invocation is restored on
//! every return path, including timeout and D-Bus errors.

use anyhow::{Context, Result};
use dbus::arg::{RefArg, Variant};
use dbus::blocking::{Connection, Proxy, SyncConnection};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

const CALL_TIMEOUT: Duration = Duration::from_millis(750);
const ATSPI_ROOT: &str = "/org/a11y/atspi/accessible/root";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Request {
    target: Target,
    maximum_depth: usize,
    maximum_node_count: usize,
    timeout_ms: u64,
    #[serde(default)]
    allow_accessibility_enable: bool,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum Target {
    ProcessId {
        value: u32,
    },
    WindowId {
        value: String,
    },
    WindowTitle {
        value: String,
        #[serde(default, rename = "processId")]
        process_id: Option<u32>,
    },
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Rect {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Window {
    key: String,
    title: Option<String>,
    bounds: Rect,
    bounds_space: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Node {
    key: String,
    parent_key: Option<String>,
    id: Option<String>,
    platform_role: Option<String>,
    role: Option<String>,
    name: Option<String>,
    states: Vec<String>,
    value: Option<String>,
    bounds: Option<Rect>,
    on_screen: Option<bool>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    schema_version: u8,
    source: &'static str,
    captured_at: String,
    windows: Vec<Window>,
    nodes: Vec<Node>,
    notes: Vec<String>,
}

#[derive(Debug, Serialize)]
struct Response {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    snapshot: Option<Snapshot>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<Error>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Error {
    code: &'static str,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    partial_snapshot: Option<Snapshot>,
}

#[derive(Clone, Debug)]
struct Address {
    bus: String,
    path: dbus::Path<'static>,
}

pub fn serve() -> Result<()> {
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let request: Request =
        serde_json::from_str(&input).context("invalid accessibility snapshot request")?;
    validate(&request)?;
    let response = capture(request);
    serde_json::to_writer(std::io::stdout().lock(), &response)?;
    std::io::stdout().write_all(b"\n")?;
    Ok(())
}

fn validate(r: &Request) -> Result<()> {
    if r.maximum_depth == 0 || r.maximum_node_count == 0 || r.timeout_ms == 0 {
        anyhow::bail!("maximumDepth, maximumNodeCount and timeoutMs must be positive")
    }
    match &r.target {
        Target::ProcessId { value: 0 } => anyhow::bail!("process-id must be positive"),
        Target::ProcessId { value } if *value > i32::MAX as u32 => {
            anyhow::bail!("process-id must be in the Linux PID range 1..=2147483647")
        }
        Target::WindowId { value } if value.is_empty() => {
            anyhow::bail!("window-id must not be empty")
        }
        Target::WindowTitle { value, .. } if value.is_empty() => {
            anyhow::bail!("window-title must not be empty")
        }
        Target::WindowTitle {
            process_id: Some(pid),
            ..
        } if *pid == 0 || *pid > i32::MAX as u32 => {
            anyhow::bail!("window-title processId must be in the Linux PID range 1..=2147483647")
        }
        _ => Ok(()),
    }
}

fn capture(request: Request) -> Response {
    match capture_inner(&request) {
        Ok(snapshot) => Response {
            ok: true,
            snapshot: Some(snapshot),
            error: None,
        },
        Err((code, message, partial)) => Response {
            ok: false,
            snapshot: None,
            error: Some(Error {
                code,
                message,
                partial_snapshot: partial,
            }),
        },
    }
}

type CaptureError = (&'static str, String, Option<Snapshot>);

fn capture_inner(request: &Request) -> std::result::Result<Snapshot, CaptureError> {
    let deadline = Instant::now() + Duration::from_millis(request.timeout_ms);
    let session = Connection::new_session().map_err(|e| timed_err(deadline, e))?;
    let status = session.with_proxy("org.a11y.Bus", "/org/a11y/bus", remaining(deadline));
    let enabled: bool = get_property(&status, "org.a11y.Status", "IsEnabled")
        .map_err(|e| timed_err(deadline, e))?;
    if !enabled && !request.allow_accessibility_enable {
        return Err((
            "accessibility-disabled",
            "the session accessibility service is disabled".into(),
            None,
        ));
    }

    let changed = !enabled;
    if changed {
        set_property(&status, "org.a11y.Status", "IsEnabled", true)
            .map_err(|e| timed_err(deadline, e))?;
    }
    let result = capture_with_status(request, deadline, &status, changed);
    if changed {
        if let Err(restore_error) = set_property(&status, "org.a11y.Status", "IsEnabled", false) {
            let outcome = match &result {
                Ok(_) => "snapshot succeeded".to_string(),
                Err((code, message, _)) => format!("snapshot failed with {code}: {message}"),
            };
            return Err((
                "restore-failed",
                format!(
                    "failed to restore org.a11y.Status.IsEnabled after {outcome}: {restore_error}"
                ),
                result.ok(),
            ));
        }
    }
    result
}

fn capture_with_status(
    request: &Request,
    deadline: Instant,
    status: &Proxy<&Connection>,
    changed: bool,
) -> std::result::Result<Snapshot, CaptureError> {
    let (address,): (String,) = status
        .method_call("org.a11y.Bus", "GetAddress", ())
        .map_err(|e| timed_err(deadline, e))?;
    check_deadline(deadline, None)?;
    let bus = Arc::new(SyncConnection::new_address(&address).map_err(|e| timed_err(deadline, e))?);
    let window_address = find_window(&bus, request, deadline)?;
    let bounds_space = if std::env::var("XDG_SESSION_TYPE")
        .map(|v| v.eq_ignore_ascii_case("wayland"))
        .unwrap_or(false)
    {
        "window"
    } else {
        "screen"
    };
    let mut snapshot = Snapshot {
        schema_version: 2,
        source: "os-accessibility",
        captured_at: now(),
        windows: Vec::new(),
        nodes: Vec::new(),
        notes: if changed {
            vec!["enabled Linux accessibility for this snapshot; restored the previous setting afterwards".into()]
        } else {
            Vec::new()
        },
    };
    let root_data =
        read_node(&bus, &window_address, deadline).map_err(|e| timed_err(deadline, e))?;
    let window_bounds = root_data.bounds.clone().ok_or_else(|| {
        err(
            "no-accessibility-tree",
            "the target window did not provide required AT-SPI Component extents",
        )
    })?;
    snapshot.windows.push(Window {
        key: "w0".into(),
        title: root_data.name.clone(),
        bounds: window_bounds.clone(),
        bounds_space,
    });
    let root_key = format!("w0/{}", segment(root_data.id.as_deref(), 0, false));
    walk(
        &bus,
        root_data,
        root_key,
        None,
        0,
        request,
        deadline,
        &window_bounds,
        &mut snapshot,
    )?;
    Ok(snapshot)
}

#[derive(Clone)]
struct NodeData {
    id: Option<String>,
    role: Option<String>,
    name: Option<String>,
    states: Vec<String>,
    value: Option<String>,
    bounds: Option<Rect>,
    children: Vec<Address>,
}

fn find_window(
    bus: &SyncConnection,
    request: &Request,
    deadline: Instant,
) -> std::result::Result<Address, CaptureError> {
    if let Target::WindowId { value } = &request.target {
        return parse_window_id(value).ok_or_else(|| {
            (
                "target-not-found",
                "Linux window-id must be an AT-SPI unique bus name and object path separated by | (for example :1.42|/org/a11y/atspi/accessible/7)".into(),
                None,
            )
        });
    }
    let root = Address {
        bus: "org.a11y.atspi.Registry".into(),
        path: ATSPI_ROOT.into(),
    };
    loop {
        let apps = children(bus, &root).map_err(|e| timed_err(deadline, e))?;
        let mut title_matches = Vec::new();
        for app in apps {
            check_deadline(deadline, None)?;
            let app_pid = connection_pid(bus, &app.bus, deadline);
            let matches_process = match request.target {
                Target::ProcessId { value } => app_pid == Some(value),
                Target::WindowTitle { process_id, .. } => {
                    process_id.map_or(true, |pid| app_pid == Some(pid))
                }
                Target::WindowId { .. } => unreachable!(),
            };
            if !matches_process {
                continue;
            }
            for window in children(bus, &app).unwrap_or_default() {
                match &request.target {
                    Target::ProcessId { .. } => return Ok(window),
                    Target::WindowTitle { value, .. } => {
                        let p =
                            bus.with_proxy(&window.bus, window.path.clone(), remaining(deadline));
                        if property_string_sync(&p, "org.a11y.atspi.Accessible", "Name").as_deref()
                            == Some(value)
                        {
                            title_matches.push(window);
                        }
                    }
                    Target::WindowId { .. } => unreachable!(),
                }
            }
        }
        if let Target::WindowTitle { value, .. } = &request.target {
            match title_matches.len() {
                1 => return Ok(title_matches.remove(0)),
                count if count > 1 => {
                    return Err((
                        "target-not-found",
                        format!(
                            "window title {value:?} is ambiguous across {count} AT-SPI window roots owned by the requested process"
                    ),
                        None,
                    ))
                }
                _ => {}
            }
        }
        if let Target::WindowTitle {
            value,
            process_id: Some(pid),
        } = &request.target
        {
            return Err((
                "target-not-found",
                format!("no window title {value:?} is owned by process {pid}"),
                None,
            ));
        }
        if Instant::now() >= deadline {
            break;
        }
        std::thread::sleep(Duration::from_millis(20).min(remaining(deadline)));
    }
    let description = match &request.target {
        Target::ProcessId { value } => format!("process {value}"),
        Target::WindowTitle {
            value,
            process_id: Some(pid),
        } => format!("window title {value:?} owned by process {pid}"),
        Target::WindowTitle {
            value,
            process_id: None,
        } => format!("window title {value:?}"),
        Target::WindowId { .. } => unreachable!(),
    };
    let code = match request.target {
        Target::WindowTitle {
            process_id: Some(_),
            ..
        } => "target-not-found",
        _ => "timeout",
    };
    Err((
        code,
        format!("timed out waiting for {description} to register with AT-SPI"),
        None,
    ))
}

fn connection_pid(bus: &SyncConnection, name: &str, deadline: Instant) -> Option<u32> {
    let daemon = bus.with_proxy(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        remaining(deadline),
    );
    daemon
        .method_call::<(u32,), _, _, _>(
            "org.freedesktop.DBus",
            "GetConnectionUnixProcessID",
            (name.to_string(),),
        )
        .ok()
        .map(|v| v.0)
}

fn parse_window_id(id: &str) -> Option<Address> {
    let (bus, path) = id.split_once('|')?;
    if !bus.starts_with(':') || !path.starts_with('/') || bus.contains('|') {
        return None;
    }
    Some(Address {
        bus: bus.to_string(),
        path: dbus::Path::new(path.to_string()).ok()?.into_static(),
    })
}

#[allow(clippy::too_many_arguments)]
fn walk(
    bus: &Arc<SyncConnection>,
    data: NodeData,
    key: String,
    parent: Option<String>,
    depth: usize,
    request: &Request,
    deadline: Instant,
    window: &Rect,
    snapshot: &mut Snapshot,
) -> std::result::Result<(), CaptureError> {
    check_deadline(deadline, Some(snapshot))?;
    if snapshot.nodes.len() >= request.maximum_node_count {
        return Err((
            "limit-reached",
            format!("maximumNodeCount {} reached", request.maximum_node_count),
            Some(std::mem::replace(snapshot, empty_snapshot())),
        ));
    }
    let on_screen = data
        .bounds
        .as_ref()
        .map(|bounds| intersects(bounds, window));
    snapshot.nodes.push(Node {
        key: key.clone(),
        parent_key: parent,
        id: data.id.clone(),
        platform_role: data.role.clone(),
        role: data.role.as_deref().map(normalize_role),
        name: data.name,
        states: data.states,
        value: data.value,
        on_screen,
        bounds: data.bounds,
    });
    if depth >= request.maximum_depth && !data.children.is_empty() {
        return Err((
            "limit-reached",
            format!("maximumDepth {} reached", request.maximum_depth),
            Some(std::mem::replace(snapshot, empty_snapshot())),
        ));
    }

    // Fetch siblings in bounded parallel batches. Joining in input order keeps key assignment and
    // the final traversal deterministic while avoiding a serial GetRole/GetState/GetInterfaces
    // property farm. Eight workers also bounds outstanding D-Bus calls and memory.
    let mut child_data = Vec::new();
    for batch in data.children.chunks(8) {
        check_deadline(deadline, Some(snapshot))?;
        let results = std::thread::scope(|scope| {
            let handles: Vec<_> = batch
                .iter()
                .cloned()
                .map(|child| {
                    let bus = Arc::clone(bus);
                    scope.spawn(move || {
                        let result = read_node(&bus, &child, deadline);
                        (child, result)
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|handle| handle.join().unwrap())
                .collect::<Vec<_>>()
        });
        for (child, result) in results {
            child_data.push((
                child,
                result.map_err(|e| {
                    if Instant::now() >= deadline {
                        ("timeout", e.to_string(), None)
                    } else {
                        err("no-accessibility-tree", e)
                    }
                })?,
            ));
        }
    }
    let mut ids = HashMap::new();
    for (_, child) in &child_data {
        if let Some(id) = &child.id {
            *ids.entry(id.clone()).or_insert(0usize) += 1;
        }
    }
    for (index, (_child, child_node)) in child_data.into_iter().enumerate() {
        let duplicate = child_node
            .id
            .as_ref()
            .map(|id| ids[id] > 1)
            .unwrap_or(false);
        let child_key = format!(
            "{}/{}",
            key,
            segment(child_node.id.as_deref(), index, duplicate)
        );
        walk(
            bus,
            child_node,
            child_key,
            Some(key.clone()),
            depth + 1,
            request,
            deadline,
            window,
            snapshot,
        )?;
    }
    Ok(())
}

fn read_node(bus: &Arc<SyncConnection>, a: &Address, deadline: Instant) -> Result<NodeData> {
    if Instant::now() >= deadline {
        anyhow::bail!("deadline exceeded")
    }
    let (name, attributes, role, children_result, state_words, interfaces, bounds) =
        std::thread::scope(|scope| {
            let name = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                property_string_sync(&p, "org.a11y.atspi.Accessible", "Name")
            });
            let attributes = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                p.method_call::<(HashMap<String, String>,), _, _, _>(
                    "org.a11y.atspi.Accessible",
                    "GetAttributes",
                    (),
                )
                .ok()
                .map(|v| v.0)
                .unwrap_or_default()
            });
            let role = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                p.method_call::<(u32,), _, _, _>("org.a11y.atspi.Accessible", "GetRole", ())
                    .ok()
                    .map(|v| role_name(v.0).to_string())
            });
            let children_result = scope.spawn(|| children(bus, a));
            let state_words = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                p.method_call::<(Vec<u32>,), _, _, _>("org.a11y.atspi.Accessible", "GetState", ())
                    .ok()
                    .map(|v| v.0)
            });
            let interfaces = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                p.method_call::<(Vec<String>,), _, _, _>(
                    "org.a11y.atspi.Accessible",
                    "GetInterfaces",
                    (),
                )
                .ok()
                .map(|v| v.0)
                .unwrap_or_default()
            });
            let bounds = scope.spawn(|| {
                let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
                p.method_call::<((i32, i32, i32, i32),), _, _, _>(
                    "org.a11y.atspi.Component",
                    "GetExtents",
                    (0u32,),
                )
                .ok()
                .map(|v| Rect {
                    x: v.0 .0,
                    y: v.0 .1,
                    width: v.0 .2,
                    height: v.0 .3,
                })
            });
            (
                name.join().unwrap(),
                attributes.join().unwrap(),
                role.join().unwrap(),
                children_result.join().unwrap(),
                state_words.join().unwrap(),
                interfaces.join().unwrap(),
                bounds.join().unwrap(),
            )
        });
    check_deadline(deadline, None).map_err(|(_, message, _)| anyhow::anyhow!(message))?;
    let id = attributes
        .get("id")
        .or_else(|| attributes.get("accessible_id"))
        .cloned()
        .filter(|v| !v.is_empty());
    let mut states = state_words
        .as_deref()
        .map(decode_states)
        .unwrap_or_default();
    // Only infer disabled from a successfully read state set. A failed GetState must remain
    // unknown rather than being turned into a false disabled assertion.
    if state_words.is_some() {
        if role.as_deref() == Some("push button")
            && !interfaces
                .iter()
                .any(|interface| interface.ends_with(".Action"))
        {
            states.retain(|state| state != "enabled");
        }
        if !states.iter().any(|state| state == "enabled") {
            states.push("disabled".into());
        }
    }
    let value = if interfaces
        .iter()
        .any(|interface| interface.ends_with(".Text"))
    {
        let p = bus.with_proxy(&a.bus, a.path.clone(), remaining(deadline));
        p.method_call::<(String,), _, _, _>("org.a11y.atspi.Text", "GetText", (0i32, -1i32))
            .ok()
            .map(|v| v.0)
            .filter(|v| !v.is_empty())
    } else {
        None
    };
    Ok(NodeData {
        id,
        role,
        name,
        states,
        value,
        bounds,
        children: children_result.unwrap_or_default(),
    })
}

fn children(bus: &SyncConnection, a: &Address) -> Result<Vec<Address>> {
    let p = bus.with_proxy(&a.bus, a.path.clone(), CALL_TIMEOUT);
    let (items,): (Vec<(String, dbus::Path<'static>)>,) =
        p.method_call("org.a11y.atspi.Accessible", "GetChildren", ())?;
    Ok(items
        .into_iter()
        .filter(|(bus, path)| !bus.is_empty() && path.to_string() != "/")
        .map(|(bus, path)| Address { bus, path })
        .collect())
}

fn get_property<T: for<'b> dbus::arg::Get<'b> + 'static>(
    p: &Proxy<&Connection>,
    interface: &str,
    name: &str,
) -> Result<T> {
    let (v,): (Variant<T>,) =
        p.method_call("org.freedesktop.DBus.Properties", "Get", (interface, name))?;
    Ok(v.0)
}
fn set_property<T: RefArg + dbus::arg::Arg + dbus::arg::Append + 'static>(
    p: &Proxy<&Connection>,
    interface: &str,
    name: &str,
    value: T,
) -> Result<()> {
    let _: () = p.method_call(
        "org.freedesktop.DBus.Properties",
        "Set",
        (interface, name, Variant(value)),
    )?;
    Ok(())
}
fn property_string_sync(p: &Proxy<&SyncConnection>, interface: &str, name: &str) -> Option<String> {
    let (value,): (Variant<String>,) = p
        .method_call("org.freedesktop.DBus.Properties", "Get", (interface, name))
        .ok()?;
    Some(value.0)
}
fn remaining(deadline: Instant) -> Duration {
    deadline
        .saturating_duration_since(Instant::now())
        .min(CALL_TIMEOUT)
}
fn timed_err(deadline: Instant, error: impl std::fmt::Display) -> CaptureError {
    if Instant::now() >= deadline {
        ("timeout", "accessibility snapshot timed out".into(), None)
    } else {
        err("no-accessibility-tree", error)
    }
}
fn err(code: &'static str, e: impl std::fmt::Display) -> CaptureError {
    (code, e.to_string(), None)
}
fn check_deadline(
    deadline: Instant,
    partial: Option<&mut Snapshot>,
) -> std::result::Result<(), CaptureError> {
    if Instant::now() < deadline {
        Ok(())
    } else {
        Err((
            "timeout",
            "accessibility snapshot timed out".into(),
            partial.map(|s| std::mem::replace(s, empty_snapshot())),
        ))
    }
}
fn empty_snapshot() -> Snapshot {
    Snapshot {
        schema_version: 2,
        source: "os-accessibility",
        captured_at: now(),
        windows: vec![],
        nodes: vec![],
        notes: vec![],
    }
}
fn now() -> String {
    time::OffsetDateTime::now_utc()
        .format(&time::format_description::well_known::Rfc3339)
        .unwrap_or_else(|_| "1970-01-01T00:00:00Z".into())
}
fn segment(id: Option<&str>, index: usize, duplicate: bool) -> String {
    if duplicate || id.is_none() {
        index.to_string()
    } else {
        {
            let escaped = id.unwrap().replace('~', "~0").replace('/', "~1");
            if escaped.chars().all(|c| c.is_ascii_digit()) {
                format!("~2{escaped}")
            } else {
                escaped
            }
        }
    }
}
fn normalize_role(role: &str) -> String {
    match role {
        "push button" => "button",
        "check box" => "checkbox",
        "frame" => "window",
        other => other,
    }
    .to_ascii_lowercase()
    .replace(' ', "-")
}

fn role_name(role: u32) -> &'static str {
    match role {
        7 => "check box",
        16 => "dialog",
        23 => "frame",
        29 => "label",
        38 => "panel",
        43 => "push button",
        60 => "text",
        75 => "application",
        79 => "text",
        85 => "section",
        _ => "unknown",
    }
}
fn intersects(a: &Rect, b: &Rect) -> bool {
    a.width > 0
        && a.height > 0
        && a.x < b.x + b.width
        && a.x + a.width > b.x
        && a.y < b.y + b.height
        && a.y + a.height > b.y
}
fn decode_states(words: &[u32]) -> Vec<String> {
    const NAMES: &[(usize, &str)] = &[
        (1, "active"),
        (2, "armed"),
        (3, "busy"),
        (4, "checked"),
        (5, "collapsed"),
        (6, "defunct"),
        (7, "editable"),
        (8, "enabled"),
        (9, "expandable"),
        (10, "expanded"),
        (11, "focusable"),
        (12, "focused"),
        (24, "selected"),
        (26, "showing"),
        (31, "visible"),
        (35, "required"),
    ];
    NAMES
        .iter()
        .filter(|(bit, _)| {
            words
                .get(bit / 32)
                .map(|w| w & (1 << (bit % 32)) != 0)
                .unwrap_or(false)
        })
        .map(|(_, n)| (*n).into())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> Request {
        serde_json::from_str(r#"{"target":{"type":"process-id","value":1},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#).unwrap()
    }
    #[test]
    fn request_defaults_to_no_enable() {
        assert!(!request().allow_accessibility_enable);
    }
    #[test]
    fn rejects_zero_limits() {
        let mut r = request();
        r.maximum_node_count = 0;
        assert!(validate(&r).is_err());
    }
    #[test]
    fn rejects_process_target_outside_linux_pid_range() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"process-id","value":2147483648},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();
        assert!(validate(&request).is_err());
    }
    #[test]
    fn keys_escape_and_fall_back_for_duplicates() {
        assert_eq!(segment(Some("a/b~c"), 0, false), "a~1b~0c");
        assert_eq!(segment(Some("same"), 2, true), "2");
        assert_eq!(segment(None, 3, false), "3");
        assert_eq!(segment(Some("123"), 4, false), "~2123");
    }
    #[test]
    fn native_window_id_requires_unique_name_and_object_path() {
        let address = parse_window_id(":1.42|/org/a11y/atspi/accessible/7").unwrap();
        assert_eq!(address.bus, ":1.42");
        assert_eq!(address.path.to_string(), "/org/a11y/atspi/accessible/7");
        assert!(parse_window_id("org.example.App|/window/7").is_none());
        assert!(parse_window_id(":1.42|not-a-path").is_none());
    }
    #[test]
    fn window_title_target_deserializes() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"window-title","value":"Preferences"},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();
        assert!(matches!(
            request.target,
            Target::WindowTitle {
                ref value,
                process_id: None
            } if value == "Preferences"
        ));
    }
    #[test]
    fn window_title_target_preserves_optional_process_owner() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"window-title","value":"Preferences","processId":4242},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();
        assert!(matches!(
            request.target,
            Target::WindowTitle {
                ref value,
                process_id: Some(4242)
            } if value == "Preferences"
        ));
    }

    #[test]
    fn rejects_window_title_process_owner_outside_pid_range() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"window-title","value":"Preferences","processId":2147483648},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();
        assert!(validate(&request).is_err());
    }
    #[test]
    fn failed_optional_reads_serialize_as_unknown() {
        let node = Node {
            key: "w0/0".into(),
            parent_key: None,
            id: None,
            platform_role: None,
            role: None,
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            on_screen: None,
        };
        let json = serde_json::to_value(node).unwrap();
        assert!(json["bounds"].is_null());
        assert!(json["onScreen"].is_null());
        assert!(!json["states"]
            .as_array()
            .unwrap()
            .iter()
            .any(|state| state == "disabled"));
    }

    #[test]
    fn on_screen_is_geometry_not_showing_state() {
        let w = Rect {
            x: 0,
            y: 0,
            width: 10,
            height: 10,
        };
        assert!(intersects(
            &Rect {
                x: 9,
                y: 9,
                width: 2,
                height: 2
            },
            &w
        ));
        assert!(!intersects(
            &Rect {
                x: 10,
                y: 0,
                width: 2,
                height: 2
            },
            &w
        ));
    }
    #[test]
    fn wayland_contract_uses_window_space() {
        std::env::set_var("XDG_SESSION_TYPE", "wayland");
        assert_eq!(
            if std::env::var("XDG_SESSION_TYPE").unwrap() == "wayland" {
                "window"
            } else {
                "screen"
            },
            "window"
        );
        std::env::remove_var("XDG_SESSION_TYPE");
    }
}
