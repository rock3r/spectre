//! Native AT-SPI2 accessibility snapshot reader.
//!
//! This deliberately talks to AT-SPI over D-Bus rather than shelling out to the Python fixture
//! probe.  The session status is guarded so a setting enabled by this invocation is restored on
//! every return path, including timeout and D-Bus errors.

use anyhow::{Context, Result};
use dbus::arg::{RefArg, Variant};
use dbus::blocking::{Connection, Proxy, SyncConnection};
use dbus::{Message, MessageType};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

const CALL_TIMEOUT: Duration = Duration::from_millis(750);
// Keep cleanup inside the JVM client's 250 ms post-deadline grace period.
const STATUS_RESTORE_TIMEOUT: Duration = Duration::from_millis(100);
// Restoration performs a Set followed by a verified Get, each with the timeout above. The JVM
// keeps the helper alive for 250 ms after its request deadline, so reserve the bounded 200 ms
// cleanup before attempting the only status-changing request.
const STATUS_RESTORE_BUDGET: Duration = Duration::from_millis(200);
// AT-SPI has no registration-complete signal after IsEnabled changes.
const REGISTRY_SETTLE_TIME: Duration = Duration::from_millis(300);
const REGISTRY_POLL_TIME: Duration = Duration::from_millis(20);
const ROOT_READ_ATTEMPTS: usize = 3;
const ATSPI_ROOT: &str = "/org/a11y/atspi/accessible/root";
const ATSPI_CACHE: &str = "/org/a11y/atspi/cache";
// Keep comfortably below the D-Bus daemon's commonly configured
// max_replies_per_connection=128. This is a call limit, not an object limit: each cached object
// can require three or four independent method calls.
const CACHE_PENDING_CALL_LIMIT: usize = 64;

type CacheRef = (String, dbus::Path<'static>);
// AT-SPI's CacheItem wire format. Unlike registry GetItems, this is requested from the
// application's own unique name, where AccessKit exposes its complete live tree.
type CachedItem = (
    CacheRef,
    CacheRef,
    CacheRef,
    i32,
    i32,
    Vec<String>,
    String,
    u32,
    String,
    Vec<u32>,
);

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
    response_from_result(capture_inner(&request))
}

fn response_from_result(result: std::result::Result<Snapshot, CaptureError>) -> Response {
    match result {
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
    let deadline = request_deadline(request)?;
    let session = Connection::new_session().map_err(|e| timed_err(deadline, e))?;
    let status = fresh_status_proxy(&session, deadline)?;
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
        let enable_deadline = deadline
            .checked_sub(STATUS_RESTORE_BUDGET)
            .unwrap_or(deadline);
        return with_prepared_status_restoration(
            || fresh_status_proxy(&session, enable_deadline),
            |status| {
                set_property(&status, "org.a11y.Status", "IsEnabled", true)
                    .map_err(|error| timed_err(enable_deadline, error))
            },
            || capture_with_status(request, deadline, &session, changed),
            || restore_status(&session),
        );
    }
    capture_with_status(request, deadline, &session, changed)
}

fn request_deadline(request: &Request) -> std::result::Result<Instant, CaptureError> {
    // JVM callers express this field as an unsigned 32-bit millisecond count. Keep the native
    // boundary equally typed instead of accepting values that cannot be represented by clients.
    if request.timeout_ms > u32::MAX as u64 {
        return Err((
            "invalid-request",
            "timeoutMs must be in the range 1..=4294967295".into(),
            None,
        ));
    }
    Instant::now()
        .checked_add(Duration::from_millis(request.timeout_ms))
        .ok_or_else(|| {
            (
                "invalid-request",
                "timeoutMs is too large for this platform".into(),
                None,
            )
        })
}

fn with_prepared_status_restoration<P>(
    prepare: impl FnOnce() -> std::result::Result<P, CaptureError>,
    enable: impl FnOnce(P) -> std::result::Result<(), CaptureError>,
    work: impl FnOnce() -> std::result::Result<Snapshot, CaptureError>,
    restore: impl FnOnce() -> std::result::Result<(), String>,
) -> std::result::Result<Snapshot, CaptureError> {
    let prepared = prepare()?;
    with_status_restoration(|| enable(prepared), work, restore)
}

fn restore_status(session: &Connection) -> std::result::Result<(), String> {
    let restore_status =
        session.with_proxy("org.a11y.Bus", "/org/a11y/bus", STATUS_RESTORE_TIMEOUT);
    let set_restored = set_property(&restore_status, "org.a11y.Status", "IsEnabled", false).is_ok();
    let verify_status = session.with_proxy("org.a11y.Bus", "/org/a11y/bus", STATUS_RESTORE_TIMEOUT);
    let restored: Result<bool> = get_property(&verify_status, "org.a11y.Status", "IsEnabled");
    restore_result(set_restored, restored.map_err(|error| error.to_string()))
}

fn with_status_restoration(
    enable: impl FnOnce() -> std::result::Result<(), CaptureError>,
    work: impl FnOnce() -> std::result::Result<Snapshot, CaptureError>,
    restore: impl FnOnce() -> std::result::Result<(), String>,
) -> std::result::Result<Snapshot, CaptureError> {
    let result = enable().and_then(|()| work());
    if let Err(restore_error) = restore() {
        let outcome = match &result {
            Ok(_) => "snapshot succeeded".to_string(),
            Err((code, message, _)) => format!("snapshot failed with {code}: {message}"),
        };
        return Err((
            "restore-failed",
            format!("failed to restore org.a11y.Status.IsEnabled after {outcome}: {restore_error}"),
            None,
        ));
    }
    result
}

fn capture_with_status(
    request: &Request,
    deadline: Instant,
    session: &Connection,
    changed: bool,
) -> std::result::Result<Snapshot, CaptureError> {
    let status = fresh_status_proxy(session, deadline)?;
    let (address,): (String,) = status
        .method_call("org.a11y.Bus", "GetAddress", ())
        .map_err(|e| timed_err(deadline, e))?;
    check_deadline(deadline, None)?;
    let bus = Arc::new(SyncConnection::new_address(&address).map_err(|e| timed_err(deadline, e))?);
    let window_addresses = find_windows(&bus, request, deadline, changed)?;
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
    for (window_index, window_address) in window_addresses.into_iter().enumerate() {
        check_deadline(deadline, Some(&mut snapshot))?;
        if snapshot.nodes.len() >= request.maximum_node_count {
            node_limit_error(request.maximum_node_count, &mut snapshot)?;
        }
        let mut provider_nodes =
            provider_cache(&bus, &window_address, deadline).map_err(|e| timed_err(deadline, e))?;
        if let Some(nodes) = provider_nodes.as_mut() {
            enrich_cached_nodes(
                &bus,
                &window_address.bus,
                &window_address.path.to_string(),
                nodes,
                request.maximum_node_count - snapshot.nodes.len(),
                request.maximum_depth,
                deadline,
            )
            .map_err(|e| timed_err(deadline, e))?;
        }
        let root_data = if let Some(nodes) = provider_nodes.as_mut() {
            nodes
                .remove(&window_address.path.to_string())
                .ok_or_else(|| {
                    err(
                        "no-accessibility-tree",
                        "the provider cache lost the selected AT-SPI root",
                    )
                })?
        } else {
            retry_selected_root_read(deadline, || read_node(&bus, &window_address, deadline))
                .map_err(|e| timed_err(deadline, e))?
        };
        let window_bounds = root_data.bounds.clone().ok_or_else(|| {
            err(
                "no-accessibility-tree",
                "the target window did not provide required AT-SPI Component extents",
            )
        })?;
        let window_key = format!("w{window_index}");
        snapshot.windows.push(Window {
            key: window_key.clone(),
            title: root_data.name.clone(),
            bounds: window_bounds.clone(),
            bounds_space,
        });
        let root_key = format!(
            "{window_key}/{}",
            segment(root_data.id.as_deref(), 0, false)
        );
        if let Some(nodes) = provider_nodes.as_mut() {
            walk_cached(
                nodes,
                root_data,
                root_key,
                None,
                0,
                request,
                deadline,
                &window_bounds,
                &mut snapshot,
            )?;
        } else {
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
        }
    }
    Ok(snapshot)
}

#[derive(Clone)]
struct NodeData {
    id: Option<String>,
    raw_role: Option<u32>,
    platform_role: Option<String>,
    name: Option<String>,
    states: Vec<String>,
    value: Option<String>,
    bounds: Option<Rect>,
    children: Vec<Address>,
    interfaces: Vec<String>,
}

#[cfg(test)]
fn cache_ref(bus: &str, path: &str) -> CacheRef {
    (bus.to_string(), path.to_string().into())
}

fn cache_ref_belongs_to(reference: &CacheRef, owner: &str) -> bool {
    reference.0 == owner && reference.1.to_string().starts_with('/')
}

fn cache_tree(selected: &Address, items: Vec<CachedItem>) -> Option<HashMap<String, NodeData>> {
    let owner = selected.bus.as_str();
    let mut raw = HashMap::new();
    for item in items {
        if !cache_ref_belongs_to(&item.0, owner)
            || !cache_ref_belongs_to(&item.1, owner)
            || (item.0 .1.to_string() != ATSPI_ROOT && !cache_ref_belongs_to(&item.2, owner))
        {
            return None;
        }
        if raw.insert(item.0 .1.to_string(), item).is_some() {
            return None;
        }
    }
    if !raw.contains_key(&selected.path.to_string()) {
        return None;
    }
    let mut children_by_parent: HashMap<String, Vec<(i32, CacheRef)>> = HashMap::new();
    for item in raw.values() {
        children_by_parent
            .entry(item.2 .1.to_string())
            .or_default()
            .push((item.3, item.0.clone()));
    }
    for children in children_by_parent.values_mut() {
        children.sort_by_key(|(index, _)| *index);
    }
    let mut result = HashMap::new();
    let mut pending = vec![selected.path.to_string()];
    while let Some(path) = pending.pop() {
        if result.contains_key(&path) {
            return None;
        }
        let item = raw.remove(&path)?;
        let children: Vec<_> = children_by_parent
            .remove(&path)
            .unwrap_or_default()
            .into_iter()
            .map(|(_, (bus, path))| Address { bus, path })
            .collect();
        if item.4 >= 0 && children.len() != item.4 as usize {
            return None;
        }
        pending.extend(children.iter().rev().map(|child| child.path.to_string()));
        result.insert(
            path,
            NodeData {
                id: None,
                raw_role: Some(item.7),
                platform_role: role_name(item.7).map(str::to_owned),
                name: (!item.6.is_empty()).then_some(item.6),
                states: decode_states(&item.9),
                value: None,
                bounds: None,
                children,
                interfaces: item.5,
            },
        );
    }
    Some(result)
}

#[cfg(test)]
fn cached_item(object: CacheRef, parent: CacheRef, index: i32, child_count: i32) -> CachedItem {
    (
        object,
        cache_ref(":1.42", ATSPI_ROOT),
        parent,
        index,
        child_count,
        vec![],
        String::new(),
        0,
        String::new(),
        vec![],
    )
}

fn provider_cache(
    bus: &SyncConnection,
    selected: &Address,
    deadline: Instant,
) -> Result<Option<HashMap<String, NodeData>>> {
    let proxy = bus.with_proxy(&selected.bus, ATSPI_CACHE, remaining(deadline));
    match proxy.method_call::<(Vec<CachedItem>,), _, _, _>("org.a11y.atspi.Cache", "GetItems", ()) {
        Ok((items,)) => Ok(cache_tree(selected, items)),
        Err(error)
            if matches!(
                error.name(),
                Some("org.freedesktop.DBus.Error.UnknownInterface")
                    | Some("org.freedesktop.DBus.Error.UnknownMethod")
                    | Some("org.freedesktop.DBus.Error.UnknownObject")
            ) =>
        {
            Ok(None)
        }
        Err(error) => Err(error.into()),
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CachedCallKind {
    Attributes,
    Bounds,
    RoleName,
    Text,
}

#[derive(Clone, Debug)]
struct PendingCachedCall {
    serial: u32,
    owner: String,
    path: String,
    kind: CachedCallKind,
}

#[derive(Clone, Debug)]
struct CachedCallSpec {
    path: String,
    kind: CachedCallKind,
}

#[derive(Debug)]
enum CachedReply {
    Attributes(HashMap<String, String>),
    Bounds(Rect),
    RoleName(String),
    Text(String),
    Unsupported,
}

fn pending_cached_call(
    serial: u32,
    owner: &str,
    path: &str,
    kind: CachedCallKind,
) -> PendingCachedCall {
    PendingCachedCall {
        serial,
        owner: owner.to_string(),
        path: path.to_string(),
        kind,
    }
}

fn enrich_cached_nodes(
    bus: &Arc<SyncConnection>,
    owner: &str,
    root_path: &str,
    nodes: &mut HashMap<String, NodeData>,
    maximum_node_count: usize,
    maximum_depth: usize,
    deadline: Instant,
) -> Result<()> {
    let (ordered, _) =
        cached_enrichment_order(nodes, root_path, maximum_node_count, maximum_depth)?;
    let extras = read_cached_extras(bus, owner, &ordered, deadline)?;
    for (path, extra) in extras {
        let node = nodes
            .get_mut(&path)
            .context("provider cache object disappeared")?;
        apply_cached_extras(node, extra);
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CachedTraversalEnd {
    Natural,
    DepthLimit,
    NodeLimit,
}

fn cached_enrichment_order(
    nodes: &HashMap<String, NodeData>,
    root_path: &str,
    maximum_node_count: usize,
    maximum_depth: usize,
) -> Result<(Vec<(String, bool)>, CachedTraversalEnd)> {
    let mut ordered = Vec::new();
    let mut pending = vec![(root_path.to_string(), 0usize)];
    while let Some((path, depth)) = pending.pop() {
        if ordered.len() >= maximum_node_count {
            return Ok((ordered, CachedTraversalEnd::NodeLimit));
        }
        let node = nodes
            .get(&path)
            .context("provider cache object disappeared")?;
        ordered.push((
            path,
            node.interfaces
                .iter()
                .any(|interface| interface.ends_with(".Text")),
        ));
        if depth >= maximum_depth && !node.children.is_empty() {
            return Ok((ordered, CachedTraversalEnd::DepthLimit));
        }
        pending.extend(
            node.children
                .iter()
                .rev()
                .map(|child| (child.path.to_string(), depth + 1)),
        );
    }
    Ok((ordered, CachedTraversalEnd::Natural))
}

fn read_cached_extras(
    bus: &SyncConnection,
    owner: &str,
    objects: &[(String, bool)],
    deadline: Instant,
) -> Result<HashMap<String, CachedExtras>> {
    if Instant::now() >= deadline {
        anyhow::bail!("deadline exceeded before cached AT-SPI enrichment batch")
    }
    let specs = cached_call_specs(objects);
    let mut result = HashMap::new();
    for window in cached_call_windows(&specs, CACHE_PENDING_CALL_LIMIT) {
        let extras = read_cached_window(bus, owner, window, deadline)?;
        for (path, extra) in extras {
            result
                .entry(path)
                .or_insert_with(CachedExtras::default)
                .merge(extra);
        }
    }
    Ok(result)
}

fn cached_call_specs(objects: &[(String, bool)]) -> Vec<CachedCallSpec> {
    let mut calls = Vec::with_capacity(objects.len() * 4);
    for (path, has_text) in objects {
        for kind in [
            CachedCallKind::Attributes,
            CachedCallKind::Bounds,
            CachedCallKind::RoleName,
        ] {
            calls.push(CachedCallSpec {
                path: path.clone(),
                kind,
            });
        }
        if *has_text {
            calls.push(CachedCallSpec {
                path: path.clone(),
                kind: CachedCallKind::Text,
            });
        }
    }
    calls
}

fn cached_call_windows(
    calls: &[CachedCallSpec],
    limit: usize,
) -> impl Iterator<Item = &[CachedCallSpec]> {
    calls.chunks(limit)
}

fn read_cached_window(
    bus: &SyncConnection,
    owner: &str,
    specs: &[CachedCallSpec],
    deadline: Instant,
) -> Result<HashMap<String, CachedExtras>> {
    let mut calls = Vec::with_capacity(specs.len());
    for spec in specs {
        let message = match spec.kind {
            CachedCallKind::Attributes => Message::call_with_args(
                owner,
                spec.path.as_str(),
                "org.a11y.atspi.Accessible",
                "GetAttributes",
                (),
            ),
            CachedCallKind::Bounds => Message::call_with_args(
                owner,
                spec.path.as_str(),
                "org.a11y.atspi.Component",
                "GetExtents",
                (0u32,),
            ),
            CachedCallKind::RoleName => Message::call_with_args(
                owner,
                spec.path.as_str(),
                "org.a11y.atspi.Accessible",
                "GetRoleName",
                (),
            ),
            CachedCallKind::Text => Message::call_with_args(
                owner,
                spec.path.as_str(),
                "org.a11y.atspi.Text",
                "GetText",
                (0i32, -1i32),
            ),
        };
        queue_cached_call(bus, owner, &spec.path, spec.kind, message, &mut calls)?;
    }

    // `send` only appends to libdbus' outgoing queue. Flush the complete bounded window before
    // waiting, otherwise a pop can wait while calls needed to produce replies remain buffered.
    bus.channel().flush();

    let calls_by_serial: HashMap<_, _> = calls
        .iter()
        .enumerate()
        .map(|(index, call)| (call.serial, index))
        .collect();
    let mut replies = HashMap::with_capacity(calls.len());
    while replies.len() < calls.len() {
        let mut message = loop {
            match bus
                .channel()
                .blocking_pop_message(call_timeout_anyhow(deadline)?)?
            {
                Some(message) => break message,
                None if Instant::now() >= deadline => {
                    anyhow::bail!("deadline exceeded waiting for cached AT-SPI enrichment replies")
                }
                None => continue,
            }
        };
        if !matches!(
            message.msg_type(),
            MessageType::MethodReturn | MessageType::Error
        ) {
            continue;
        }
        let Some(serial) = message.get_reply_serial() else {
            continue;
        };
        let Some(call) = calls_by_serial
            .get(&serial)
            .map(|index| &calls[*index])
            .filter(|call| {
                cached_reply_matches(message.msg_type(), message.sender().as_deref(), &call.owner)
            })
        else {
            continue;
        };
        let reply = match message.as_result() {
            Ok(message) => decode_cached_reply(message, call.kind)?,
            Err(error) => classify_cached_error(&error, call.kind, deadline)?,
        };
        replies.insert(serial, reply);
    }
    assemble_cached_extras(&calls, replies)
}

fn cached_reply_matches(kind: MessageType, sender: Option<&str>, owner: &str) -> bool {
    matches!(kind, MessageType::MethodReturn | MessageType::Error)
        && matches!(sender, Some(value) if value == owner || (kind == MessageType::Error && value == "org.freedesktop.DBus"))
}

fn classify_cached_error(
    error: &dbus::Error,
    kind: CachedCallKind,
    deadline: Instant,
) -> Result<CachedReply> {
    if is_dbus_timeout_error(error) || Instant::now() >= deadline {
        return Err(dbus::Error::new_custom(
            error.name().unwrap_or("org.freedesktop.DBus.Error.Failed"),
            &error.to_string(),
        )
        .into());
    }
    match error.name() {
        Some(
            "org.freedesktop.DBus.Error.UnknownInterface"
            | "org.freedesktop.DBus.Error.UnknownMethod"
            | "org.freedesktop.DBus.Error.UnknownObject",
        ) => Ok(CachedReply::Unsupported),
        // AT-SPI providers commonly use these for an optional attribute unavailable on one node.
        Some("org.a11y.atspi.Error.NotImplemented" | "org.a11y.atspi.Error.NotSupported") => {
            Ok(CachedReply::Unsupported)
        }
        _ => anyhow::bail!("cached AT-SPI {kind:?} call failed: {error}"),
    }
}

fn queue_cached_call(
    bus: &SyncConnection,
    owner: &str,
    path: &str,
    kind: CachedCallKind,
    message: Message,
    calls: &mut Vec<PendingCachedCall>,
) -> Result<()> {
    let serial = bus
        .channel()
        .send(message)
        .map_err(|()| anyhow::anyhow!("failed to queue cached AT-SPI enrichment call"))?;
    calls.push(pending_cached_call(serial, owner, path, kind));
    Ok(())
}

fn decode_cached_reply(message: &Message, kind: CachedCallKind) -> Result<CachedReply> {
    Ok(match kind {
        CachedCallKind::Attributes => {
            CachedReply::Attributes(message.read1::<HashMap<String, String>>()?)
        }
        CachedCallKind::Bounds => {
            let (x, y, width, height) = message.read1::<(i32, i32, i32, i32)>()?;
            CachedReply::Bounds(Rect {
                x,
                y,
                width,
                height,
            })
        }
        CachedCallKind::RoleName => CachedReply::RoleName(message.read1::<String>()?),
        CachedCallKind::Text => CachedReply::Text(message.read1::<String>()?),
    })
}

fn assemble_cached_extras(
    calls: &[PendingCachedCall],
    mut replies: HashMap<u32, CachedReply>,
) -> Result<HashMap<String, CachedExtras>> {
    let mut result = HashMap::new();
    for call in calls {
        let reply = replies
            .remove(&call.serial)
            .context("cached AT-SPI enrichment reply was not matched")?;
        let extra = result
            .entry(call.path.clone())
            .or_insert_with(CachedExtras::default);
        match reply {
            CachedReply::Attributes(attributes) => {
                extra.id = attributes
                    .get("id")
                    .or_else(|| attributes.get("accessible_id"))
                    .cloned()
                    .filter(|value| !value.is_empty());
            }
            CachedReply::Bounds(bounds) => extra.bounds = Some(bounds),
            CachedReply::RoleName(value) => {
                extra.provider_role_name = (!value.is_empty()).then_some(value)
            }
            CachedReply::Text(value) => extra.value = (!value.is_empty()).then_some(value),
            CachedReply::Unsupported => {}
        }
    }
    Ok(result)
}

#[derive(Default)]
struct CachedExtras {
    id: Option<String>,
    bounds: Option<Rect>,
    value: Option<String>,
    provider_role_name: Option<String>,
}

impl CachedExtras {
    fn merge(&mut self, other: Self) {
        if other.id.is_some() {
            self.id = other.id;
        }
        if other.bounds.is_some() {
            self.bounds = other.bounds;
        }
        if other.value.is_some() {
            self.value = other.value;
        }
        if other.provider_role_name.is_some() {
            self.provider_role_name = other.provider_role_name;
        }
    }
}

fn apply_cached_extras(node: &mut NodeData, extras: CachedExtras) {
    node.id = extras.id;
    node.bounds = extras.bounds;
    node.value = extras.value;
    if let Some(provider_role_name) = extras.provider_role_name {
        node.platform_role = Some(provider_role_name);
    }
}

fn optional_cached_call<T>(
    deadline: Instant,
    call: impl FnOnce() -> Result<T>,
) -> Result<Option<T>> {
    if Instant::now() >= deadline {
        anyhow::bail!("deadline exceeded before cached AT-SPI enrichment call")
    }
    match call() {
        Ok(value) => Ok(Some(value)),
        Err(error) if is_timeout_error(&error) || Instant::now() >= deadline => Err(error),
        Err(_) => Ok(None),
    }
}

fn find_windows(
    bus: &SyncConnection,
    request: &Request,
    deadline: Instant,
    status_changed: bool,
) -> std::result::Result<Vec<Address>, CaptureError> {
    if let Target::WindowId { value } = &request.target {
        return parse_window_id(value).map(|window| vec![window]).ok_or_else(|| {
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
    let settle_unscoped_title = status_changed
        && matches!(
            request.target,
            Target::WindowTitle {
                process_id: None,
                ..
            }
        );
    let owner_scoped = matches!(
        request.target,
        Target::WindowTitle {
            process_id: Some(_),
            ..
        }
    );
    let mut unique_title_seen: Option<(Address, Instant)> = None;
    let mut completed_authoritative_traversal = false;
    loop {
        if !should_start_registry_round(
            Instant::now(),
            deadline,
            owner_scoped,
            completed_authoritative_traversal,
        ) {
            break;
        }
        let apps = children(bus, &root, deadline).map_err(|e| timed_err(deadline, e))?;
        let mut title_matches = Vec::new();
        let mut process_matches = Vec::new();
        let mut traversal_authoritative = true;
        for app in apps {
            if Instant::now() >= deadline {
                return Err(find_window_expired(request));
            }
            let app_pid = connection_pid(bus, &app.bus, deadline)?;
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
            let windows = match children(bus, &app, deadline) {
                Ok(windows) => windows,
                Err(error) if is_transient_registration_error(&error) => {
                    traversal_authoritative = false;
                    continue;
                }
                Err(error) => return Err(timed_err(deadline, error)),
            };
            for window in windows {
                match &request.target {
                    Target::ProcessId { .. } => process_matches.push(window),
                    Target::WindowTitle { value, .. } => {
                        match find_window_title(bus, &window, deadline)? {
                            TitleRead::Value(title) if title == *value => {
                                title_matches.push(window)
                            }
                            TitleRead::TransientUnavailable => traversal_authoritative = false,
                            TitleRead::Value(_) => {}
                        }
                    }
                    Target::WindowId { .. } => unreachable!(),
                }
            }
        }
        completed_authoritative_traversal |= traversal_authoritative;
        if matches!(request.target, Target::ProcessId { .. }) {
            if let Some(windows) = completed_process_scan(process_matches, traversal_authoritative)
            {
                return Ok(windows);
            }
        }
        if let Target::WindowTitle { value, .. } = &request.target {
            match unscoped_title_decision(
                title_matches,
                settle_unscoped_title,
                traversal_authoritative,
                unique_title_seen,
                Instant::now(),
            ) {
                TitleDecision::Accept(window) => return Ok(vec![window]),
                TitleDecision::Ambiguous(count) => {
                    return Err((
                        "target-not-found",
                        format!(
                            "window title {value:?} is ambiguous across {count} AT-SPI window roots owned by the requested process"
                        ),
                        None,
                    ));
                }
                TitleDecision::Retry(seen) => unique_title_seen = seen,
            }
        }
        if Instant::now() >= deadline {
            break;
        }
        std::thread::sleep(REGISTRY_POLL_TIME.min(remaining(deadline)));
    }
    Err(find_window_expired(request))
}

fn completed_process_scan(
    matches: Vec<Address>,
    traversal_authoritative: bool,
) -> Option<Vec<Address>> {
    (traversal_authoritative && !matches.is_empty()).then_some(matches)
}

enum TitleDecision {
    Accept(Address),
    Ambiguous(usize),
    Retry(Option<(Address, Instant)>),
}

fn unscoped_title_decision(
    mut matches: Vec<Address>,
    settle: bool,
    traversal_authoritative: bool,
    previous: Option<(Address, Instant)>,
    now: Instant,
) -> TitleDecision {
    if matches.len() > 1 {
        return TitleDecision::Ambiguous(matches.len());
    }
    if !traversal_authoritative {
        return TitleDecision::Retry(None);
    }
    match matches.len() {
        1 if !settle => TitleDecision::Accept(matches.remove(0)),
        1 => {
            let candidate = matches.remove(0);
            match previous {
                Some((previous_candidate, seen))
                    if previous_candidate.bus == candidate.bus
                        && previous_candidate.path == candidate.path
                        && now.saturating_duration_since(seen) >= REGISTRY_SETTLE_TIME =>
                {
                    TitleDecision::Accept(candidate)
                }
                Some((previous_candidate, seen))
                    if previous_candidate.bus == candidate.bus
                        && previous_candidate.path == candidate.path =>
                {
                    TitleDecision::Retry(Some((candidate, seen)))
                }
                _ => TitleDecision::Retry(Some((candidate, now))),
            }
        }
        _ => TitleDecision::Retry(None),
    }
}

fn should_start_registry_round(
    now: Instant,
    deadline: Instant,
    owner_scoped: bool,
    completed_authoritative_traversal: bool,
) -> bool {
    if now >= deadline {
        return false;
    }
    !owner_scoped
        || !completed_authoritative_traversal
        || deadline.saturating_duration_since(now) > REGISTRY_POLL_TIME
}

fn is_transient_registration_error(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<dbus::Error>()
        .and_then(dbus::Error::name)
        .is_some_and(|name| name == "org.freedesktop.DBus.Error.UnknownInterface")
}

fn is_transient_selected_root_error(error: &anyhow::Error) -> bool {
    error.downcast_ref::<dbus::Error>().is_some_and(|error| {
        error.name() == Some("org.freedesktop.DBus.Error.UnknownInterface")
            && error
                .message()
                .is_some_and(|message| message.contains("org.a11y.atspi.Accessible"))
    })
}

fn retry_selected_root_read<T>(
    deadline: Instant,
    mut read: impl FnMut() -> Result<T>,
) -> Result<T> {
    for attempt in 0..ROOT_READ_ATTEMPTS {
        if Instant::now() >= deadline {
            anyhow::bail!("deadline exceeded while reading the selected AT-SPI root")
        }
        match read() {
            Ok(value) => return Ok(value),
            Err(error)
                if attempt + 1 < ROOT_READ_ATTEMPTS && is_transient_selected_root_error(&error) =>
            {
                std::thread::sleep(REGISTRY_POLL_TIME.min(remaining(deadline)));
            }
            Err(error) => return Err(error),
        }
    }
    unreachable!()
}

fn restore_result(
    set_succeeded: bool,
    readback: std::result::Result<bool, String>,
) -> std::result::Result<(), String> {
    match readback {
        Ok(false) => Ok(()),
        Ok(true) => Err("IsEnabled remained true after the restore attempt".into()),
        Err(error) if set_succeeded => Err(format!("could not confirm restored state: {error}")),
        Err(error) => Err(format!(
            "restore call and state confirmation failed: {error}"
        )),
    }
}

fn find_window_expired(request: &Request) -> CaptureError {
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
    (
        code,
        format!("timed out waiting for {description} to register with AT-SPI"),
        None,
    )
}

fn connection_pid(
    bus: &SyncConnection,
    name: &str,
    deadline: Instant,
) -> std::result::Result<Option<u32>, CaptureError> {
    let daemon = bus.with_proxy(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        remaining(deadline),
    );
    match daemon.method_call::<(u32,), _, _, _>(
        "org.freedesktop.DBus",
        "GetConnectionUnixProcessID",
        (name.to_string(),),
    ) {
        Ok(value) => Ok(Some(value.0)),
        Err(error) => optional_find_read(deadline, error),
    }
}

enum TitleRead {
    Value(String),
    TransientUnavailable,
}

fn find_window_title(
    bus: &SyncConnection,
    window: &Address,
    deadline: Instant,
) -> std::result::Result<TitleRead, CaptureError> {
    let proxy = bus.with_proxy(&window.bus, window.path.clone(), call_timeout(deadline)?);
    match proxy.method_call::<(Variant<String>,), _, _, _>(
        "org.freedesktop.DBus.Properties",
        "Get",
        ("org.a11y.atspi.Accessible", "Name"),
    ) {
        Ok(value) => Ok(TitleRead::Value(value.0 .0)),
        Err(error) if is_transient_title_error(&error) => Ok(TitleRead::TransientUnavailable),
        Err(error) => Err(timed_err(deadline, error)),
    }
}

fn is_transient_title_error(error: &dbus::Error) -> bool {
    error.name() == Some("org.freedesktop.DBus.Error.UnknownInterface")
}

fn optional_find_read<T>(
    deadline: Instant,
    error: dbus::Error,
) -> std::result::Result<Option<T>, CaptureError> {
    let classified = timed_err(deadline, error);
    if classified.0 == "timeout" {
        Err(classified)
    } else {
        // Providers can disappear between registry enumeration and an individual read.
        Ok(None)
    }
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
fn walk_cached(
    nodes: &mut HashMap<String, NodeData>,
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
        return node_limit_error(request.maximum_node_count, snapshot);
    }
    let on_screen = data
        .bounds
        .as_ref()
        .map(|bounds| intersects(bounds, window));
    snapshot.nodes.push(Node {
        key: key.clone(),
        parent_key: parent,
        id: data.id.clone(),
        platform_role: data.platform_role,
        role: data.raw_role.and_then(normalize_raw_role),
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
    let prefetch_count = child_prefetch_count(
        snapshot.nodes.len(),
        request.maximum_node_count,
        data.children.len(),
    );
    let children_truncated = prefetch_count < data.children.len();
    let mut children = Vec::with_capacity(prefetch_count);
    for address in &data.children[..prefetch_count] {
        let child = nodes.remove(&address.path.to_string()).ok_or_else(|| {
            structural_read_error("provider cache child missing", deadline, snapshot)
        })?;
        children.push(child);
    }
    let mut ids = HashMap::new();
    for child in &children {
        if let Some(id) = &child.id {
            *ids.entry(id.clone()).or_insert(0usize) += 1;
        }
    }
    for (index, child) in children.into_iter().enumerate() {
        let duplicate = child.id.as_ref().is_some_and(|id| ids[id] > 1);
        let child_key = format!(
            "{}/{}",
            key,
            child_segment(child.id.as_deref(), index, duplicate, children_truncated)
        );
        walk_cached(
            nodes,
            child,
            child_key,
            Some(key.clone()),
            depth + 1,
            request,
            deadline,
            window,
            snapshot,
        )?;
    }
    if children_truncated {
        return node_limit_error(request.maximum_node_count, snapshot);
    }
    Ok(())
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
    walk_with_reader(
        data,
        key,
        parent,
        depth,
        request,
        deadline,
        window,
        snapshot,
        &|child, deadline| read_node(bus, child, deadline),
    )
}

#[allow(clippy::too_many_arguments)]
fn walk_with_reader(
    data: NodeData,
    key: String,
    parent: Option<String>,
    depth: usize,
    request: &Request,
    deadline: Instant,
    window: &Rect,
    snapshot: &mut Snapshot,
    read: &impl Fn(&Address, Instant) -> Result<NodeData>,
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
        platform_role: data.platform_role,
        role: data.raw_role.and_then(normalize_raw_role),
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

    let prefetch_count = child_prefetch_count(
        snapshot.nodes.len(),
        request.maximum_node_count,
        data.children.len(),
    );
    if prefetch_count == 0 && !data.children.is_empty() {
        return node_limit_error(request.maximum_node_count, snapshot);
    }
    let children_truncated = prefetch_count < data.children.len();

    let mut completed_children = Vec::new();
    for (index, child_address) in data.children[..prefetch_count].iter().enumerate() {
        check_deadline(deadline, Some(snapshot))?;
        if snapshot.nodes.len() >= request.maximum_node_count {
            return node_limit_error(request.maximum_node_count, snapshot);
        }
        let child_node = read(child_address, deadline)
            .map_err(|error| structural_read_error(error, deadline, snapshot))?;
        let child_id = child_node.id.clone();
        // Until every sibling has been read, only a positional key is safe: an unread sibling may
        // duplicate this id. A completed, non-truncated set is renamed below.
        let provisional_key = format!("{key}/{index}");
        let child_start = snapshot.nodes.len();
        walk_with_reader(
            child_node,
            provisional_key,
            Some(key.clone()),
            depth + 1,
            request,
            deadline,
            window,
            snapshot,
            read,
        )?;
        completed_children.push((child_start, snapshot.nodes.len(), child_id));
    }
    if !children_truncated {
        let mut ids = HashMap::new();
        for (_, _, id) in &completed_children {
            if let Some(id) = id {
                *ids.entry(id.clone()).or_insert(0usize) += 1;
            }
        }
        for (index, (start, end, id)) in completed_children.into_iter().enumerate() {
            let duplicate = id.as_ref().is_some_and(|id| ids[id] > 1);
            let old_prefix = format!("{key}/{index}");
            let new_prefix = format!(
                "{key}/{}",
                child_segment(id.as_deref(), index, duplicate, false)
            );
            rename_subtree(&mut snapshot.nodes[start..end], &old_prefix, &new_prefix);
        }
    }
    if children_truncated {
        return node_limit_error(request.maximum_node_count, snapshot);
    }
    Ok(())
}

fn rename_subtree(nodes: &mut [Node], old_prefix: &str, new_prefix: &str) {
    for node in nodes {
        if node.key == old_prefix || node.key.starts_with(&format!("{old_prefix}/")) {
            node.key = format!("{new_prefix}{}", &node.key[old_prefix.len()..]);
        }
        if let Some(parent) = &mut node.parent_key {
            if parent == old_prefix || parent.starts_with(&format!("{old_prefix}/")) {
                *parent = format!("{new_prefix}{}", &parent[old_prefix.len()..]);
            }
        }
    }
}

fn child_prefetch_count(current: usize, maximum: usize, child_count: usize) -> usize {
    maximum.saturating_sub(current).min(child_count)
}

fn child_segment(id: Option<&str>, index: usize, duplicate: bool, truncated: bool) -> String {
    // An unread sibling may repeat an apparently unique id. Positional keys make a partial
    // snapshot agree with a complete traversal in that case.
    segment(id, index, duplicate || truncated)
}

fn node_limit_error(
    maximum_node_count: usize,
    snapshot: &mut Snapshot,
) -> std::result::Result<(), CaptureError> {
    Err((
        "limit-reached",
        format!("maximumNodeCount {maximum_node_count} reached"),
        Some(std::mem::replace(snapshot, empty_snapshot())),
    ))
}

fn structural_read_error(
    error: impl std::fmt::Display,
    deadline: Instant,
    snapshot: &mut Snapshot,
) -> CaptureError {
    let (code, message) = if Instant::now() >= deadline {
        ("timeout", "accessibility snapshot timed out".to_string())
    } else {
        (
            "no-accessibility-tree",
            format!("failed to read an AT-SPI child: {error}"),
        )
    };
    let partial = (code == "timeout").then(|| std::mem::replace(snapshot, empty_snapshot()));
    (code, message, partial)
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
                let raw = optional_role_call(deadline, || {
                    let p = bus.with_proxy(&a.bus, a.path.clone(), call_timeout_anyhow(deadline)?);
                    p.method_call::<(u32,), _, _, _>("org.a11y.atspi.Accessible", "GetRole", ())
                        .map(|value| value.0)
                        .map_err(Into::into)
                })?;
                let native_name = optional_role_call(deadline, || {
                    let p = bus.with_proxy(&a.bus, a.path.clone(), call_timeout_anyhow(deadline)?);
                    p.method_call::<(String,), _, _, _>(
                        "org.a11y.atspi.Accessible",
                        "GetRoleName",
                        (),
                    )
                    .map(|value| value.0)
                    .map_err(Into::into)
                })?;
                Ok::<_, anyhow::Error>((raw, native_name))
            });
            let children_result = scope.spawn(|| children(bus, a, deadline));
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
    let states = state_words
        .as_deref()
        .map(decode_states)
        .unwrap_or_default();
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
    let (raw_role, native_role_name) = role.context("AT-SPI role reads failed")?;
    Ok(NodeData {
        id,
        raw_role,
        platform_role: native_role_name.or_else(|| raw_role.and_then(role_name).map(str::to_owned)),
        name,
        states,
        value,
        bounds,
        children: children_result.context("AT-SPI GetChildren failed")?,
        interfaces,
    })
}

fn children(bus: &SyncConnection, a: &Address, deadline: Instant) -> Result<Vec<Address>> {
    let timeout = remaining(deadline);
    if timeout.is_zero() {
        anyhow::bail!("deadline exceeded before AT-SPI GetChildren")
    }
    let p = bus.with_proxy(&a.bus, a.path.clone(), timeout);
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
fn fresh_status_proxy(
    session: &Connection,
    deadline: Instant,
) -> std::result::Result<Proxy<'_, &Connection>, CaptureError> {
    Ok(session.with_proxy("org.a11y.Bus", "/org/a11y/bus", call_timeout(deadline)?))
}
fn call_timeout(deadline: Instant) -> std::result::Result<Duration, CaptureError> {
    let timeout = remaining(deadline);
    if timeout.is_zero() {
        Err(("timeout", "accessibility snapshot timed out".into(), None))
    } else {
        Ok(timeout)
    }
}
fn call_timeout_anyhow(deadline: Instant) -> Result<Duration> {
    let timeout = remaining(deadline);
    if timeout.is_zero() {
        anyhow::bail!("deadline exceeded before AT-SPI role read")
    }
    Ok(timeout)
}
fn optional_role_call<T>(deadline: Instant, call: impl FnOnce() -> Result<T>) -> Result<Option<T>> {
    match call() {
        Ok(value) => Ok(Some(value)),
        Err(error) => {
            let timed_out = Instant::now() >= deadline || is_timeout_error(&error);
            if timed_out {
                Err(error)
            } else {
                Ok(None)
            }
        }
    }
}
fn is_dbus_timeout_error(error: &dbus::Error) -> bool {
    error.name().is_some_and(|name| {
        matches!(
            name,
            "org.freedesktop.DBus.Error.NoReply"
                | "org.freedesktop.DBus.Error.Timeout"
                | "org.freedesktop.DBus.Error.TimedOut"
        )
    })
}
fn is_timeout_error(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<dbus::Error>()
        .and_then(dbus::Error::name)
        .is_some_and(|name| {
            matches!(
                name,
                "org.freedesktop.DBus.Error.NoReply"
                    | "org.freedesktop.DBus.Error.Timeout"
                    | "org.freedesktop.DBus.Error.TimedOut"
            )
        })
}
fn remaining(deadline: Instant) -> Duration {
    deadline
        .saturating_duration_since(Instant::now())
        .min(CALL_TIMEOUT)
}
fn timed_err(deadline: Instant, error: impl Into<anyhow::Error>) -> CaptureError {
    let error = error.into();
    // libdbus uses millisecond timeouts and can report expiration before the Rust deadline.
    let dbus_timeout = is_timeout_error(&error);
    if dbus_timeout || Instant::now() >= deadline {
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
// Values are in the exact order of AtspiRole in atspi-constants.h. Keep the raw-role
// normalization independent of GetRoleName: the latter is the authoritative native platformRole,
// while this table supplies a stable schema role and covers every protocol value we support.
const ATSPI_ROLE_NAMES: &[&str] = &[
    "invalid",
    "accelerator label",
    "alert",
    "animation",
    "arrow",
    "calendar",
    "canvas",
    "check box",
    "check menu item",
    "color chooser",
    "column header",
    "combo box",
    "date editor",
    "desktop icon",
    "desktop frame",
    "dial",
    "dialog",
    "directory pane",
    "drawing area",
    "file chooser",
    "filler",
    "focus traversable",
    "font chooser",
    "frame",
    "glass pane",
    "html container",
    "icon",
    "image",
    "internal frame",
    "label",
    "layered pane",
    "list",
    "list item",
    "menu",
    "menu bar",
    "menu item",
    "option pane",
    "page tab",
    "page tab list",
    "panel",
    "password text",
    "popup menu",
    "progress bar",
    "button",
    "radio button",
    "radio menu item",
    "root pane",
    "row header",
    "scroll bar",
    "scroll pane",
    "separator",
    "slider",
    "spin button",
    "split pane",
    "status bar",
    "table",
    "table cell",
    "table column header",
    "table row header",
    "tearoff menu item",
    "terminal",
    "text",
    "toggle button",
    "tool bar",
    "tool tip",
    "tree",
    "tree table",
    "unknown",
    "viewport",
    "window",
    "extended",
    "header",
    "footer",
    "paragraph",
    "ruler",
    "application",
    "autocomplete",
    "editbar",
    "embedded",
    "entry",
    "chart",
    "caption",
    "document frame",
    "heading",
    "page",
    "section",
    "redundant object",
    "form",
    "link",
    "input method window",
    "table row",
    "tree item",
    "document spreadsheet",
    "document presentation",
    "document text",
    "document web",
    "document email",
    "comment",
    "list box",
    "grouping",
    "image map",
    "notification",
    "info bar",
    "level bar",
    "title bar",
    "block quote",
    "audio",
    "video",
    "definition",
    "article",
    "landmark",
    "log",
    "marquee",
    "math",
    "rating",
    "timer",
    "static",
    "math fraction",
    "math root",
    "subscript",
    "superscript",
    "description list",
    "description term",
    "description value",
    "footnote",
    "content deletion",
    "content insertion",
    "mark",
    "suggestion",
    "push button menu",
    "switch",
];

fn role_name(role: u32) -> Option<&'static str> {
    ATSPI_ROLE_NAMES.get(role as usize).copied()
}

fn normalize_raw_role(role: u32) -> Option<String> {
    role_name(role).map(|name| {
        match name {
            "push button menu" => "button-menu",
            "check box" => "checkbox",
            "frame" => "window",
            other => other,
        }
        .replace(' ', "-")
    })
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
        (13, "has-tooltip"),
        (14, "horizontal"),
        (15, "iconified"),
        (16, "modal"),
        (17, "multi-line"),
        (18, "multiselectable"),
        (19, "opaque"),
        (20, "pressed"),
        (21, "resizable"),
        (22, "selectable"),
        (23, "selected"),
        (24, "sensitive"),
        (25, "showing"),
        (26, "single-line"),
        (27, "stale"),
        (28, "transient"),
        (29, "vertical"),
        (30, "visible"),
        (31, "manages-descendants"),
        (32, "indeterminate"),
        (33, "required"),
        (34, "truncated"),
        (35, "animated"),
        (36, "invalid-entry"),
        (37, "supports-autocompletion"),
        (38, "selectable-text"),
        (39, "is-default"),
        (40, "visited"),
        (41, "checkable"),
        (42, "has-popup"),
        (43, "read-only"),
        (44, "default"),
    ];
    let mut states: Vec<String> = NAMES
        .iter()
        .filter(|(bit, _)| {
            words
                .get(bit / 32)
                .map(|word| word & (1 << (bit % 32)) != 0)
                .unwrap_or(false)
        })
        .map(|(_, name)| (*name).into())
        .collect();
    // Some GTK providers expose SENSITIVE without ENABLED. Action support is independent.
    if !words.is_empty()
        && !states
            .iter()
            .any(|state| state == "enabled" || state == "sensitive")
    {
        states.push("disabled".into());
    }
    states
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn atspi_roles_cover_protocol_constants_without_aliasing_distinct_widgets() {
        let cases = [
            (7, "check box", "checkbox"),
            (37, "page tab", "page-tab"),
            (38, "page tab list", "page-tab-list"),
            (39, "panel", "panel"),
            (43, "button", "button"),
            (60, "terminal", "terminal"),
            (61, "text", "text"),
            (67, "unknown", "unknown"),
            (79, "entry", "entry"),
            (129, "push button menu", "button-menu"),
        ];
        for (raw, platform, normalized) in cases {
            assert_eq!(role_name(raw), Some(platform), "raw role {raw}");
            assert_eq!(normalize_raw_role(raw), Some(normalized.to_string()));
        }
        assert_eq!(role_name(10_000), None);
        assert_eq!(normalize_raw_role(10_000), None);
    }

    #[test]
    fn atspi_state_words_use_the_protocol_bit_offsets() {
        let words = [(1 << 23) | (1 << 24) | (1 << 25) | (1 << 30), 1 << 1];
        assert_eq!(
            decode_states(&words),
            ["selected", "sensitive", "showing", "visible", "required"]
        );
    }
    #[test]
    fn sensitive_buttons_preserve_the_provider_state_words() {
        // GNOME GTK button and AccessKit Save changes, respectively.
        assert_eq!(
            decode_states(&[1124075520, 0]),
            ["focusable", "sensitive", "showing", "visible"]
        );
        assert_eq!(
            decode_states(&[1124075776, 0]),
            ["enabled", "focusable", "sensitive", "showing", "visible"]
        );
        assert!(decode_states(&[]).is_empty());
        assert_eq!(decode_states(&[0, 0]), ["disabled"]);
    }

    #[test]
    fn newly_enabled_unscoped_title_waits_for_a_stable_registry() {
        let candidate = Address {
            bus: ":1.7".into(),
            path: "/window/1".into(),
        };
        let first_seen = Instant::now();
        assert!(
            matches!(unscoped_title_decision(vec![candidate.clone()], true, true, Some((candidate.clone(), first_seen)), first_seen + REGISTRY_SETTLE_TIME / 2), TitleDecision::Retry(Some((_, seen))) if seen == first_seen)
        );
        assert!(matches!(
            unscoped_title_decision(
                vec![candidate.clone()],
                true,
                true,
                Some((candidate, first_seen)),
                first_seen + REGISTRY_SETTLE_TIME
            ),
            TitleDecision::Accept(_)
        ));
    }

    #[test]
    fn late_second_unscoped_title_is_rejected_during_settling() {
        let matches = vec![
            Address {
                bus: ":1.7".into(),
                path: "/window/1".into(),
            },
            Address {
                bus: ":1.8".into(),
                path: "/window/1".into(),
            },
        ];
        assert!(matches!(
            unscoped_title_decision(matches, true, false, None, Instant::now()),
            TitleDecision::Ambiguous(2)
        ));
    }

    #[test]
    fn incomplete_title_scan_clears_singleton_stability_before_later_ambiguity() {
        let candidate = Address {
            bus: ":1.7".into(),
            path: "/window/1".into(),
        };
        let first_seen = Instant::now() - REGISTRY_SETTLE_TIME;
        assert!(matches!(
            unscoped_title_decision(
                vec![candidate.clone()],
                true,
                false,
                Some((candidate, first_seen)),
                Instant::now()
            ),
            TitleDecision::Retry(None)
        ));

        let matches = vec![
            Address {
                bus: ":1.7".into(),
                path: "/window/1".into(),
            },
            Address {
                bus: ":1.8".into(),
                path: "/window/1".into(),
            },
        ];
        assert!(matches!(
            unscoped_title_decision(matches, true, true, None, Instant::now()),
            TitleDecision::Ambiguous(2)
        ));
    }

    #[test]
    fn complete_singleton_title_scan_can_be_accepted() {
        let candidate = Address {
            bus: ":1.7".into(),
            path: "/window/1".into(),
        };
        assert!(matches!(
            unscoped_title_decision(vec![candidate], false, true, None, Instant::now()),
            TitleDecision::Accept(_)
        ));
    }

    #[test]
    fn selected_root_retries_only_accessible_unknown_interface() {
        let mut calls = 0;
        let result = retry_selected_root_read(Instant::now() + Duration::from_secs(1), || {
            calls += 1;
            if calls == 1 {
                Err(anyhow::Error::new(dbus::Error::new_custom(
                    "org.freedesktop.DBus.Error.UnknownInterface",
                    "No such interface 'org.a11y.atspi.Accessible'",
                ))
                .context("AT-SPI GetChildren failed"))
            } else {
                Ok(42)
            }
        });
        assert_eq!(result.unwrap(), 42);
        assert_eq!(calls, 2);

        for (name, message) in [
            (
                "org.freedesktop.DBus.Error.UnknownInterface",
                "org.a11y.atspi.Component",
            ),
            (
                "org.freedesktop.DBus.Error.AccessDenied",
                "org.a11y.atspi.Accessible",
            ),
            (
                "org.freedesktop.DBus.Error.Failed",
                "Permanent AT-SPI failure",
            ),
        ] {
            let mut calls = 0;
            let result = retry_selected_root_read(Instant::now() + Duration::from_secs(1), || {
                calls += 1;
                Err::<(), _>(
                    anyhow::Error::new(dbus::Error::new_custom(name, message))
                        .context("AT-SPI GetChildren failed"),
                )
            });
            assert!(result.is_err());
            assert_eq!(calls, 1);
        }
    }

    #[test]
    fn selected_root_retry_respects_expired_deadline() {
        let mut calls = 0;
        let result = retry_selected_root_read(Instant::now(), || {
            calls += 1;
            Ok(())
        });
        assert!(result.is_err());
        assert_eq!(calls, 0);
    }

    #[test]
    fn completed_owner_scan_does_not_begin_a_dbus_round_at_the_deadline_edge() {
        let now = Instant::now();
        let deadline = now + REGISTRY_POLL_TIME;
        assert!(!should_start_registry_round(now, deadline, true, true));
        assert!(should_start_registry_round(now, deadline, true, false));
        assert!(should_start_registry_round(now, deadline, false, true));
    }

    #[test]
    fn restore_requires_confirmed_readback() {
        assert_eq!(restore_result(true, Ok(false)), Ok(()));
        assert_eq!(restore_result(false, Ok(false)), Ok(()));
        assert!(restore_result(false, Ok(true)).is_err());
        assert!(restore_result(true, Err("read failed".into())).is_err());
    }

    #[test]
    fn applied_enable_with_lost_reply_is_restored_before_returning() {
        use std::cell::Cell;

        let enabled = Cell::new(false);
        let restore_calls = Cell::new(0);
        let result = with_status_restoration(
            || {
                enabled.set(true);
                Err(("timeout", "enable reply was lost".into(), None))
            },
            || panic!("capture must not run after an enable error"),
            || {
                restore_calls.set(restore_calls.get() + 1);
                enabled.set(false);
                Ok(())
            },
        );

        assert_eq!(result.unwrap_err().0, "timeout");
        assert!(!enabled.get(), "the mock desktop setting must be reverted");
        assert_eq!(restore_calls.get(), 1);
    }

    #[test]
    fn each_sequential_call_gets_only_the_current_remaining_budget() {
        let deadline = Instant::now() + Duration::from_millis(80);
        let first = call_timeout(deadline).unwrap();
        std::thread::sleep(Duration::from_millis(20));
        let second = call_timeout(deadline).unwrap();
        assert!(second < first, "a later proxy must not reuse {first:?}");

        let request_deadline = Instant::now() + Duration::from_millis(300);
        let mutation_deadline = request_deadline - STATUS_RESTORE_BUDGET;
        assert!(call_timeout(mutation_deadline).unwrap() <= Duration::from_millis(100));
    }

    #[test]
    fn only_unknown_interface_makes_a_title_scan_non_authoritative() {
        let transient = dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.UnknownInterface",
            "Accessible is still registering",
        );
        let permanent =
            dbus::Error::new_custom("org.freedesktop.DBus.Error.AccessDenied", "title denied");
        assert!(is_transient_title_error(&transient));
        assert!(!is_transient_title_error(&permanent));

        let candidate = Address {
            bus: ":1.7".into(),
            path: "/window/1".into(),
        };
        assert!(matches!(
            unscoped_title_decision(vec![candidate], false, false, None, Instant::now()),
            TitleDecision::Retry(None)
        ));
    }

    #[test]
    fn preparation_failure_does_not_arm_restoration() {
        use std::cell::Cell;
        let enable_calls = Cell::new(0);
        let restore_calls = Cell::new(0);
        let result = with_prepared_status_restoration(
            || Err::<(), _>(("timeout", "initial status read timed out".into(), None)),
            |()| {
                enable_calls.set(enable_calls.get() + 1);
                Ok(())
            },
            || panic!("capture must not run"),
            || {
                restore_calls.set(restore_calls.get() + 1);
                Ok(())
            },
        );
        assert_eq!(result.unwrap_err().0, "timeout");
        assert_eq!(enable_calls.get(), 0);
        assert_eq!(restore_calls.get(), 0);
    }

    #[test]
    fn attempted_enable_with_lost_reply_is_restored() {
        use std::cell::Cell;
        let restore_calls = Cell::new(0);
        let result = with_prepared_status_restoration(
            || Ok(()),
            |()| Err(("timeout", "enable reply was lost".into(), None)),
            || panic!("capture must not run"),
            || {
                restore_calls.set(restore_calls.get() + 1);
                Ok(())
            },
        );
        assert_eq!(result.unwrap_err().0, "timeout");
        assert_eq!(restore_calls.get(), 1);
    }

    #[test]
    fn restore_failure_wire_never_contains_a_partial_snapshot() {
        let successful_capture = with_status_restoration(
            || Ok(()),
            || Ok(empty_snapshot()),
            || Err("readback failed".into()),
        );
        let failed_capture = with_status_restoration(
            || Ok(()),
            || {
                Err((
                    "timeout",
                    "capture timed out".into(),
                    Some(empty_snapshot()),
                ))
            },
            || Err("readback failed".into()),
        );

        for result in [successful_capture, failed_capture] {
            let encoded = serde_json::to_value(response_from_result(result)).unwrap();
            assert_eq!(encoded["ok"], false);
            assert_eq!(encoded["error"]["code"], "restore-failed");
            assert!(encoded["error"]["message"]
                .as_str()
                .unwrap()
                .contains("readback failed"));
            assert!(
                encoded["error"].get("partialSnapshot").is_none(),
                "{encoded}"
            );
            assert!(encoded.get("snapshot").is_none(), "{encoded}");
        }
    }

    #[test]
    fn cleanup_budget_fits_the_daemon_grace_period() {
        assert_eq!(STATUS_RESTORE_BUDGET, STATUS_RESTORE_TIMEOUT * 2);
        assert!(STATUS_RESTORE_BUDGET < Duration::from_millis(250));
    }

    #[test]
    fn unknown_interface_is_transient_only_during_registration() {
        let unknown = anyhow::Error::new(dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.UnknownInterface",
            "not registered yet",
        ));
        let denied = anyhow::Error::new(dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.AccessDenied",
            "denied",
        ));
        assert!(is_transient_registration_error(&unknown));
        assert!(!is_transient_registration_error(&denied));
    }

    #[test]
    fn dbus_error_classification_before_deadline() {
        let deadline = Instant::now() + Duration::from_secs(30);
        for name in [
            "org.freedesktop.DBus.Error.NoReply",
            "org.freedesktop.DBus.Error.Timeout",
            "org.freedesktop.DBus.Error.TimedOut",
        ] {
            let cause = dbus::Error::new_custom(name, "Did not receive a reply.");
            let error = anyhow::Error::new(cause).context("reading accessibility status");
            assert_eq!(timed_err(deadline, error).0, "timeout", "{name}");
        }
        let error = dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.AccessDenied",
            "Did not receive a reply.",
        );
        assert_eq!(timed_err(deadline, error).0, "no-accessibility-tree");
    }

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
    fn cached_enrichment_stops_at_first_depth_boundary() {
        let root_path = "/root".to_string();
        let first_path = "/first".to_string();
        let missing_later_path = "/later".to_string();
        let grandchild_path = "/grandchild".to_string();
        let address = |path: &str| Address {
            bus: ":1.42".into(),
            path: path.to_string().into(),
        };
        let node = |children: Vec<Address>| NodeData {
            id: None,
            raw_role: None,
            platform_role: None,
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            children,
            interfaces: vec![],
        };
        let nodes = HashMap::from([
            (
                root_path.clone(),
                node(vec![address(&first_path), address(&missing_later_path)]),
            ),
            (first_path.clone(), node(vec![address(&grandchild_path)])),
        ]);

        // The absent later sibling instruments a provider call that would fail (or hang). It is
        // deliberately unreachable because walking stops as soon as the first child proves the
        // depth limit.
        let (selected, end) = cached_enrichment_order(&nodes, &root_path, 10, 1).unwrap();
        assert_eq!(
            selected,
            vec![(root_path.clone(), false), (first_path, false)]
        );
        assert_eq!(end, CachedTraversalEnd::DepthLimit);

        let (selected, end) = cached_enrichment_order(&nodes, &root_path, 1, 10).unwrap();
        assert_eq!(selected, vec![(root_path.clone(), false)]);
        assert_eq!(end, CachedTraversalEnd::NodeLimit);

        let leaf_path = "/leaf".to_string();
        let leaves = HashMap::from([(leaf_path.clone(), node(vec![]))]);
        let (selected, end) = cached_enrichment_order(&leaves, &leaf_path, 1, 0).unwrap();
        assert_eq!(selected, vec![(leaf_path, false)]);
        assert_eq!(end, CachedTraversalEnd::Natural);
    }

    #[test]
    fn cached_walk_reports_depth_limit_with_the_selected_prefix() {
        let address = |path: &str| Address {
            bus: ":1.42".into(),
            path: path.to_string().into(),
        };
        let data = |id: &str, children: Vec<Address>| NodeData {
            id: Some(id.into()),
            raw_role: None,
            platform_role: None,
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            children,
            interfaces: vec![],
        };
        let mut root = data("root", vec![address("/first"), address("/later")]);
        let mut nodes = HashMap::from([
            (
                "/first".into(),
                data("repeated", vec![address("/grandchild")]),
            ),
            ("/later".into(), data("repeated", vec![])),
        ]);
        let mut request = request();
        request.maximum_depth = 1;
        request.maximum_node_count = 10;
        let mut snapshot = empty_snapshot();
        let result = walk_cached(
            &mut nodes,
            root.clone(),
            "w0/root".into(),
            None,
            0,
            &request,
            Instant::now() + Duration::from_secs(1),
            &Rect {
                x: 0,
                y: 0,
                width: 1,
                height: 1,
            },
            &mut snapshot,
        );
        let (code, message, partial) = result.unwrap_err();
        assert_eq!(code, "limit-reached");
        assert_eq!(message, "maximumDepth 1 reached");
        let partial = partial.unwrap();
        assert_eq!(
            partial
                .nodes
                .iter()
                .map(|node| node.key.as_str())
                .collect::<Vec<_>>(),
            ["w0/root", "w0/root/0"]
        );

        root.children.clear();
        request.maximum_depth = 0;
        let mut snapshot = empty_snapshot();
        assert!(walk_cached(
            &mut HashMap::new(),
            root,
            "w0/root".into(),
            None,
            0,
            &request,
            Instant::now() + Duration::from_secs(1),
            &Rect {
                x: 0,
                y: 0,
                width: 1,
                height: 1
            },
            &mut snapshot,
        )
        .is_ok());
        assert_eq!(snapshot.nodes.len(), 1);
    }

    fn test_address(path: &str) -> Address {
        Address {
            bus: ":1.42".into(),
            path: path.to_string().into(),
        }
    }

    fn test_node(id: &str, children: Vec<Address>) -> NodeData {
        NodeData {
            id: Some(id.into()),
            raw_role: None,
            platform_role: None,
            name: Some(id.into()),
            states: vec![],
            value: None,
            bounds: None,
            children,
            interfaces: vec![],
        }
    }

    #[test]
    fn uncached_walk_stops_before_late_sibling_at_depth_boundary() {
        use std::sync::Mutex;

        let root = test_node(
            "root",
            vec![test_address("/first"), test_address("/late_failing")],
        );
        let calls = Mutex::new(Vec::new());
        let read = |child: &Address, _| {
            calls.lock().unwrap().push(child.path.to_string());
            match child.path.to_string().as_str() {
                "/first" => Ok(test_node("first", vec![test_address("/grandchild")])),
                _ => anyhow::bail!("late sibling must not be requested"),
            }
        };
        let mut request = request();
        request.maximum_depth = 1;
        request.maximum_node_count = 10;
        let mut snapshot = empty_snapshot();
        let result = walk_with_reader(
            root,
            "w0/root".into(),
            None,
            0,
            &request,
            Instant::now() + Duration::from_secs(1),
            &Rect {
                x: 0,
                y: 0,
                width: 1,
                height: 1,
            },
            &mut snapshot,
            &read,
        );

        let (code, message, partial) = result.unwrap_err();
        assert_eq!(
            (code, message.as_str()),
            ("limit-reached", "maximumDepth 1 reached")
        );
        let partial = partial.unwrap();
        assert_eq!(calls.into_inner().unwrap(), ["/first"]);
        assert_eq!(partial.nodes.len(), 2);
        assert_eq!(partial.nodes[1].key, "w0/root/0");
    }

    #[test]
    fn uncached_walk_checks_dynamic_node_budget_before_late_sibling_read() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        let late_calls = AtomicUsize::new(0);
        let read = |child: &Address, _| match child.path.to_string().as_str() {
            "/branch" => Ok(test_node("branch", vec![test_address("/leaf")])),
            "/leaf" => Ok(test_node("leaf", vec![])),
            "/late_poison" => {
                late_calls.fetch_add(1, Ordering::SeqCst);
                anyhow::bail!("late poison must not be requested")
            }
            path => anyhow::bail!("unexpected read: {path}"),
        };
        let mut request = request();
        request.maximum_depth = 10;
        request.maximum_node_count = 3;
        let mut snapshot = empty_snapshot();
        let result = walk_with_reader(
            test_node(
                "root",
                vec![test_address("/branch"), test_address("/late_poison")],
            ),
            "w0/root".into(),
            None,
            0,
            &request,
            Instant::now() + Duration::from_secs(1),
            &Rect {
                x: 0,
                y: 0,
                width: 1,
                height: 1,
            },
            &mut snapshot,
            &read,
        );

        let (code, message, partial) = result.unwrap_err();
        assert_eq!(
            (code, message.as_str()),
            ("limit-reached", "maximumNodeCount 3 reached")
        );
        assert_eq!(late_calls.load(Ordering::SeqCst), 0);
        let partial = partial.unwrap();
        assert_eq!(
            partial
                .nodes
                .iter()
                .map(|node| (node.key.as_str(), node.parent_key.as_deref()))
                .collect::<Vec<_>>(),
            [
                ("w0/root", None),
                ("w0/root/0", Some("w0/root")),
                ("w0/root/0/leaf", Some("w0/root/0")),
            ]
        );
    }

    #[test]
    fn uncached_walk_preserves_complete_mixed_tree_keys_and_parent_links() {
        let tree: HashMap<String, NodeData> = HashMap::from([
            (
                "/unique".into(),
                test_node("unique", vec![test_address("/nested")]),
            ),
            ("/nested".into(), test_node("nested", vec![])),
            ("/duplicate_a".into(), test_node("same", vec![])),
            ("/duplicate_b".into(), test_node("same", vec![])),
        ]);
        let read = |address: &Address, _| {
            tree.get(&address.path.to_string())
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("unexpected read"))
        };
        let mut request = request();
        request.maximum_depth = 10;
        request.maximum_node_count = 10;
        let mut snapshot = empty_snapshot();
        walk_with_reader(
            test_node(
                "root",
                vec![
                    test_address("/unique"),
                    test_address("/duplicate_a"),
                    test_address("/duplicate_b"),
                ],
            ),
            "w0/root".into(),
            None,
            0,
            &request,
            Instant::now() + Duration::from_secs(1),
            &Rect {
                x: 0,
                y: 0,
                width: 1,
                height: 1,
            },
            &mut snapshot,
            &read,
        )
        .unwrap();

        assert_eq!(
            snapshot
                .nodes
                .iter()
                .map(|node| (node.key.as_str(), node.parent_key.as_deref()))
                .collect::<Vec<_>>(),
            [
                ("w0/root", None),
                ("w0/root/unique", Some("w0/root")),
                ("w0/root/unique/nested", Some("w0/root/unique")),
                ("w0/root/1", Some("w0/root")),
                ("w0/root/2", Some("w0/root")),
            ]
        );
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
    fn tiny_budget_bounds_prefetch_and_keeps_repeated_sibling_key_stable() {
        assert_eq!(child_prefetch_count(1, 1, 10), 0);
        assert_eq!(child_prefetch_count(1, 3, 10), 2);
        assert_eq!(child_prefetch_count(3, 3, 10), 0);

        // A truncated sibling list cannot prove that an id is unique. Use the positional form
        // that a complete traversal also uses when a later sibling repeats the id.
        assert_eq!(child_segment(Some("repeated"), 0, false, true), "0");
        assert_eq!(child_segment(Some("repeated"), 0, true, false), "0");
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
    fn provider_cache_rejects_cross_owner_references() {
        let own = cache_ref(":1.42", "/window/7");
        let foreign = cache_ref(":1.99", "/window/8");
        assert!(cache_ref_belongs_to(&own, ":1.42"));
        assert!(!cache_ref_belongs_to(&foreign, ":1.42"));
    }

    #[test]
    fn provider_cache_is_used_only_when_it_contains_the_selected_root() {
        let selected = Address {
            bus: ":1.42".into(),
            path: "/window/7".into(),
        };
        let item = cached_item(
            cache_ref(":1.42", "/window/8"),
            cache_ref(":1.42", ATSPI_ROOT),
            0,
            0,
        );
        assert!(cache_tree(&selected, vec![item]).is_none());
    }

    #[test]
    fn provider_cache_preserves_children_names_roles_states_and_order() {
        let selected = Address {
            bus: ":1.42".into(),
            path: "/window/7".into(),
        };
        let mut root = cached_item(
            cache_ref(":1.42", "/window/7"),
            cache_ref(":1.42", ATSPI_ROOT),
            0,
            2,
        );
        root.6 = "Fixture window".into();
        root.7 = 68;
        root.9 = vec![(1 << 24) | (1 << 30), 0];
        let child_a = cache_ref(":1.42", "/node/a");
        let child_b = cache_ref(":1.42", "/node/b");
        let leaf_a = cached_item(child_a.clone(), cache_ref(":1.42", "/window/7"), 0, 0);
        let leaf_b = cached_item(child_b.clone(), cache_ref(":1.42", "/window/7"), 1, 0);
        let tree = cache_tree(&selected, vec![root, leaf_a, leaf_b]).unwrap();
        let data = tree.get(&selected.path.to_string()).unwrap();
        assert_eq!(data.name.as_deref(), Some("Fixture window"));
        assert_eq!(data.raw_role, Some(68));
        assert_eq!(data.states, ["sensitive", "visible"]);
        assert_eq!(data.children[0].path, child_a.1);
        assert_eq!(data.children[1].path, child_b.1);
    }

    #[test]
    fn cached_enrichment_preserves_provider_role_name_and_numeric_normalization() {
        let selected = Address {
            bus: ":1.42".into(),
            path: "/window/7".into(),
        };
        let mut item = cached_item(
            cache_ref(":1.42", "/window/7"),
            cache_ref(":1.42", ATSPI_ROOT),
            0,
            0,
        );
        item.7 = 39;
        let mut tree = cache_tree(&selected, vec![item]).unwrap();
        let panel = tree.get_mut("/window/7").unwrap();

        apply_cached_extras(
            panel,
            CachedExtras {
                id: None,
                bounds: None,
                value: None,
                provider_role_name: Some("generic".into()),
            },
        );

        assert_eq!(panel.platform_role.as_deref(), Some("generic"));
        assert_eq!(
            panel.raw_role.and_then(normalize_raw_role).as_deref(),
            Some("panel")
        );
    }

    #[test]
    fn cached_enrichment_keeps_known_provider_name_for_unknown_numeric_role() {
        let mut unknown = NodeData {
            id: None,
            raw_role: Some(10_000),
            platform_role: None,
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            children: vec![],
            interfaces: vec![],
        };

        apply_cached_extras(
            &mut unknown,
            CachedExtras {
                id: None,
                bounds: None,
                value: None,
                provider_role_name: Some("custom widget".into()),
            },
        );

        assert_eq!(unknown.platform_role.as_deref(), Some("custom widget"));
        assert_eq!(unknown.raw_role.and_then(normalize_raw_role), None);
    }

    #[test]
    fn cached_enrichment_falls_back_when_provider_role_name_is_unsupported() {
        let mut entry = NodeData {
            id: None,
            raw_role: Some(79),
            platform_role: role_name(79).map(str::to_owned),
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            children: vec![],
            interfaces: vec![],
        };

        apply_cached_extras(
            &mut entry,
            CachedExtras {
                id: None,
                bounds: None,
                value: None,
                provider_role_name: None,
            },
        );

        assert_eq!(entry.platform_role.as_deref(), Some("entry"));
        assert_eq!(
            entry.raw_role.and_then(normalize_raw_role).as_deref(),
            Some("entry")
        );
    }

    #[test]
    fn cached_pipeline_matches_out_of_order_replies_to_exact_object_and_call() {
        let calls = vec![
            pending_cached_call(11, ":1.42", "/node/a", CachedCallKind::Attributes),
            pending_cached_call(12, ":1.42", "/node/a", CachedCallKind::RoleName),
            pending_cached_call(13, ":1.42", "/node/b", CachedCallKind::RoleName),
        ];
        let mut replies = HashMap::new();
        replies.insert(13, CachedReply::RoleName("custom b".into()));
        replies.insert(
            11,
            CachedReply::Attributes(HashMap::from([("id".into(), "leaf-a".into())])),
        );
        replies.insert(12, CachedReply::RoleName("generic".into()));

        let extras = assemble_cached_extras(&calls, replies).unwrap();
        assert_eq!(extras["/node/a"].id.as_deref(), Some("leaf-a"));
        assert_eq!(
            extras["/node/a"].provider_role_name.as_deref(),
            Some("generic")
        );
        assert_eq!(
            extras["/node/b"].provider_role_name.as_deref(),
            Some("custom b")
        );
    }

    #[test]
    fn cached_pipeline_does_not_generalize_one_objects_unsupported_role() {
        let calls = vec![
            pending_cached_call(21, ":1.42", "/node/a", CachedCallKind::RoleName),
            pending_cached_call(22, ":1.42", "/node/b", CachedCallKind::RoleName),
        ];
        let replies = HashMap::from([
            (21, CachedReply::Unsupported),
            (22, CachedReply::RoleName("generic".into())),
        ]);

        let extras = assemble_cached_extras(&calls, replies).unwrap();
        assert_eq!(extras["/node/a"].provider_role_name, None);
        assert_eq!(
            extras["/node/b"].provider_role_name.as_deref(),
            Some("generic")
        );
    }

    #[test]
    fn cached_pipeline_windows_by_call_count_not_object_count() {
        let objects = vec![
            ("/node/a".to_string(), true),
            ("/node/b".to_string(), false),
            ("/node/c".to_string(), true),
        ];
        let calls = cached_call_specs(&objects);
        let windows = cached_call_windows(&calls, 4)
            .map(|window| window.len())
            .collect::<Vec<_>>();

        assert_eq!(calls.len(), 11);
        assert_eq!(windows, [4, 4, 3]);
    }

    #[test]
    fn cached_pipeline_rejects_foreign_errors_and_returns() {
        assert!(cached_reply_matches(
            MessageType::Error,
            Some("org.freedesktop.DBus"),
            ":1.42"
        ));
        assert!(cached_reply_matches(
            MessageType::Error,
            Some(":1.42"),
            ":1.42"
        ));
        assert!(!cached_reply_matches(
            MessageType::Error,
            Some(":1.99"),
            ":1.42"
        ));
        assert!(!cached_reply_matches(
            MessageType::MethodReturn,
            Some(":1.99"),
            ":1.42"
        ));
    }

    #[test]
    fn cached_pipeline_only_hides_optional_provider_errors() {
        let deadline = Instant::now() + Duration::from_secs(30);
        for name in [
            "org.freedesktop.DBus.Error.UnknownMethod",
            "org.a11y.atspi.Error.NotImplemented",
        ] {
            let error = dbus::Error::new_custom(name, "optional field unavailable");
            assert!(matches!(
                classify_cached_error(&error, CachedCallKind::RoleName, deadline).unwrap(),
                CachedReply::Unsupported
            ));
        }
        for name in [
            "org.freedesktop.DBus.Error.LimitsExceeded",
            "org.freedesktop.DBus.Error.Disconnected",
            "org.freedesktop.DBus.Error.ServiceUnknown",
        ] {
            let error = dbus::Error::new_custom(name, "required route failed");
            assert!(classify_cached_error(&error, CachedCallKind::Attributes, deadline).is_err());
        }
    }

    #[test]
    fn timeout_milliseconds_that_do_not_fit_an_instant_are_rejected() {
        let mut request = request();
        request.timeout_ms = u64::MAX;
        let error = request_deadline(&request).unwrap_err();
        assert_eq!(error.0, "invalid-request");
        assert!(error.1.contains("timeoutMs"));
    }

    #[test]
    fn cached_optional_reads_check_deadline_and_preserve_timeout_errors() {
        let called = std::cell::Cell::new(false);
        let result = optional_cached_call::<u32>(Instant::now(), || {
            called.set(true);
            Ok(7)
        });
        assert!(result.is_err());
        assert!(!called.get());

        let deadline = Instant::now() + Duration::from_secs(30);
        let unsupported = dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.UnknownMethod",
            "GetRoleName is not implemented",
        );
        assert_eq!(
            optional_cached_call::<String>(deadline, || Err(unsupported.into())).unwrap(),
            None
        );
        let error =
            dbus::Error::new_custom("org.freedesktop.DBus.Error.NoReply", "provider timed out");
        assert!(optional_cached_call::<u32>(deadline, || Err(error.into())).is_err());
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
    fn owner_scoped_title_expiration_is_target_not_found() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"window-title","value":"Preferences","processId":4242},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();

        let error = find_window_expired(&request);

        assert_eq!(error.0, "target-not-found");
        assert!(error.1.contains("owned by process 4242"));
    }

    #[test]
    fn process_scan_returns_every_window_in_registry_order() {
        let windows = vec![test_address("/window/9"), test_address("/window/2")];
        let selected = completed_process_scan(windows, true).unwrap();
        assert_eq!(
            selected
                .iter()
                .map(|window| window.path.to_string())
                .collect::<Vec<_>>(),
            ["/window/9", "/window/2"]
        );
        assert!(completed_process_scan(vec![test_address("/window/9")], false).is_none());
        assert!(completed_process_scan(Vec::new(), true).is_none());
    }

    #[test]
    fn unscoped_window_search_expiration_remains_timeout() {
        let request: Request = serde_json::from_str(
            r#"{"target":{"type":"window-title","value":"Preferences"},"maximumDepth":2,"maximumNodeCount":3,"timeoutMs":4}"#,
        )
        .unwrap();

        assert_eq!(find_window_expired(&request).0, "timeout");
    }

    #[test]
    fn unresponsive_find_window_dbus_read_remains_timeout() {
        let deadline = Instant::now() + Duration::from_secs(30);
        let error = dbus::Error::new_custom(
            "org.freedesktop.DBus.Error.NoReply",
            "Did not receive a reply.",
        );

        let result = optional_find_read::<u32>(deadline, error);

        assert_eq!(result.unwrap_err().0, "timeout");
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
    fn structural_failure_discards_the_available_partial_snapshot() {
        let mut snapshot = empty_snapshot();
        snapshot.nodes.push(Node {
            key: "w0/root".into(),
            parent_key: None,
            id: None,
            platform_role: None,
            role: None,
            name: Some("available".into()),
            states: vec![],
            value: None,
            bounds: None,
            on_screen: None,
        });

        let (code, message, partial) = structural_read_error(
            "org.example.StructuralFailure",
            Instant::now() + Duration::from_secs(1),
            &mut snapshot,
        );

        assert_eq!(code, "no-accessibility-tree");
        assert!(message.contains("StructuralFailure"));
        assert!(partial.is_none());
        assert_eq!(snapshot.nodes.len(), 1);
    }

    #[test]
    fn stalled_structural_read_is_a_typed_timeout_with_partial_data() {
        let mut snapshot = empty_snapshot();
        snapshot.nodes.push(Node {
            key: "w0/root".into(),
            parent_key: None,
            id: None,
            platform_role: None,
            role: None,
            name: None,
            states: vec![],
            value: None,
            bounds: None,
            on_screen: None,
        });

        let (code, _, partial) =
            structural_read_error("deadline exceeded", Instant::now(), &mut snapshot);

        assert_eq!(code, "timeout");
        assert_eq!(partial.unwrap().nodes.len(), 1);
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
