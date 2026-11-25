use accesskit::{Action, Node, NodeId, Rect, Role, Toggled, TreeId, TreeInfo, TreeUpdate};

pub const ROOT_ID: NodeId = NodeId(0);
pub const POPOVER_TRIGGER_ID: NodeId = NodeId(12);
pub const POPOVER_ID: NodeId = NodeId(14);

const BUTTON_ID: NodeId = NodeId(1);
const CHECKBOX_ID: NodeId = NodeId(2);
const TEXT_ID: NodeId = NodeId(3);
const GROUP_A_ID: NodeId = NodeId(4);
const GROUP_B_ID: NodeId = NodeId(5);
const DISABLED_ID: NodeId = NodeId(11);
const OFFSCREEN_ID: NodeId = NodeId(13);

fn node(role: Role, id: &str, label: &str, bounds: Rect) -> Node {
    let mut node = Node::new(role);
    // Native identifiers are backed by author_id; html_id is retained for AXDOMIdentifier and
    // backends that expose the HTML-shaped attribute.
    node.set_author_id(id);
    node.set_html_id(id);
    node.set_label(label);
    node.set_bounds(bounds);
    node
}

fn rect(y: f64) -> Rect {
    Rect {
        x0: 20.0,
        y0: y,
        x1: 300.0,
        y1: y + 36.0,
    }
}

/// Returns the complete deterministic tree consumed by each native-reader integration test.
/// NodeIds are implementation details; `html_id` intentionally contains duplicate platform IDs.
pub fn initial_tree(popover_open: bool) -> TreeUpdate {
    let mut root = node(
        Role::Window,
        "fixture-window",
        "Spectre AccessKit Fixture",
        Rect {
            x0: 0.0,
            y0: 0.0,
            x1: 640.0,
            y1: 480.0,
        },
    );
    let mut children = vec![
        BUTTON_ID,
        CHECKBOX_ID,
        TEXT_ID,
        GROUP_A_ID,
        GROUP_B_ID,
        DISABLED_ID,
        POPOVER_TRIGGER_ID,
        OFFSCREEN_ID,
    ];
    if popover_open {
        children.push(POPOVER_ID);
    }
    root.set_children(children);

    let mut button = node(Role::Button, "primary-button", "Save changes", rect(20.0));
    button.add_action(Action::Click);
    button.add_action(Action::Focus);
    let mut checkbox = node(Role::CheckBox, "newsletter", "Receive updates", rect(70.0));
    checkbox.set_toggled(Toggled::True);
    checkbox.add_action(Action::Click);
    let mut text = node(Role::TextInput, "account-name", "Account name", rect(120.0));
    text.set_value("Ada Lovelace");
    text.add_action(Action::Focus);
    text.add_action(Action::SetValue);

    let mut group_a = node(Role::Group, "group-a", "First group", rect(170.0));
    group_a.set_children(vec![NodeId(6), NodeId(7)]);
    let mut group_b = node(Role::Group, "group-b", "Second group", rect(270.0));
    group_b.set_children(vec![NodeId(8), NodeId(9)]);
    let duplicate_a = node(
        Role::Label,
        "repeated",
        "Repeated in first group",
        rect(210.0),
    );
    let duplicate_b = node(Role::Label, "repeated", "Repeated sibling one", rect(310.0));
    let duplicate_c = node(Role::Label, "repeated", "Repeated sibling two", rect(350.0));
    let mut nested = node(Role::Group, "nested-group", "Nested group", rect(390.0));
    nested.set_children(vec![NodeId(10)]);
    let nested_label = node(Role::Label, "nested-label", "Nested content", rect(430.0));

    let mut disabled = node(
        Role::Button,
        "disabled-button",
        "Unavailable action",
        rect(400.0),
    );
    disabled.set_disabled();
    let mut trigger = node(Role::Button, "popover-trigger", "Open details", rect(440.0));
    trigger.add_action(Action::Click);
    let offscreen = node(
        Role::Button,
        "offscreen-button",
        "Scrolled away",
        rect(1200.0),
    );

    let mut nodes = vec![
        (ROOT_ID, root),
        (BUTTON_ID, button),
        (CHECKBOX_ID, checkbox),
        (TEXT_ID, text),
        (GROUP_A_ID, group_a),
        (GROUP_B_ID, group_b),
        (NodeId(6), duplicate_a),
        (NodeId(7), nested),
        (NodeId(8), duplicate_b),
        (NodeId(9), duplicate_c),
        (NodeId(10), nested_label),
        (DISABLED_ID, disabled),
        (POPOVER_TRIGGER_ID, trigger),
        (OFFSCREEN_ID, offscreen),
    ];
    if popover_open {
        let mut popover = node(
            Role::Dialog,
            "details-popover",
            "Details",
            Rect {
                x0: 320.0,
                y0: 100.0,
                x1: 600.0,
                y1: 220.0,
            },
        );
        popover.set_children(vec![NodeId(15)]);
        nodes.push((POPOVER_ID, popover));
        nodes.push((
            NodeId(15),
            node(
                Role::Label,
                "popover-content",
                "Overlay content",
                rect(140.0),
            ),
        ));
    }
    TreeUpdate {
        nodes,
        tree: Some(TreeInfo::new(ROOT_ID)),
        tree_id: TreeId::ROOT,
        focus: BUTTON_ID,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_tree_covers_reader_edge_cases() {
        let update = initial_tree(false);
        assert_eq!(update.nodes.len(), 14);
        let repeated = update
            .nodes
            .iter()
            .filter(|(_, node)| node.html_id() == Some("repeated"))
            .count();
        assert_eq!(
            repeated, 3,
            "duplicate IDs cover siblings and different parents"
        );
        assert!(update
            .nodes
            .iter()
            .all(|(_, node)| node.author_id() == node.html_id()));
        assert!(update
            .nodes
            .iter()
            .any(|(_, node)| node.value() == Some("Ada Lovelace")));
        assert!(update
            .nodes
            .iter()
            .any(|(_, node)| node.bounds().is_some_and(|bounds| bounds.y0 > 480.0)));
    }

    #[test]
    fn disabled_intent_is_source_level_and_not_inferred_from_actions() {
        let update = initial_tree(false);
        let find = |id| {
            update
                .nodes
                .iter()
                .find(|(node_id, _)| *node_id == id)
                .map(|(_, node)| node)
                .expect("fixture control must exist")
        };
        let disabled = find(DISABLED_ID);
        let offscreen = find(OFFSCREEN_ID);

        assert_eq!(disabled.author_id(), Some("disabled-button"));
        assert_eq!(disabled.label(), Some("Unavailable action"));
        assert_eq!(disabled.role(), Role::Button);
        assert!(disabled.is_disabled());
        assert_eq!(offscreen.author_id(), Some("offscreen-button"));
        assert_eq!(offscreen.label(), Some("Scrolled away"));
        assert_eq!(offscreen.role(), Role::Button);
        assert!(!offscreen.is_disabled());
        assert!(offscreen.bounds().is_some_and(|bounds| bounds.y0 > 480.0));
    }

    #[test]
    fn duplicate_author_ids_retain_two_distinct_parent_contracts() {
        let update = initial_tree(false);
        let parents: Vec<_> = update
            .nodes
            .iter()
            .filter(|(_, node)| {
                node.children().iter().any(|child_id| {
                    update
                        .nodes
                        .iter()
                        .any(|(id, child)| id == child_id && child.author_id() == Some("repeated"))
                })
            })
            .map(|(id, _)| *id)
            .collect();
        assert_eq!(parents, vec![GROUP_A_ID, GROUP_B_ID]);
    }

    #[test]
    fn popover_is_added_only_on_demand() {
        assert_eq!(initial_tree(false).nodes.len(), 14);
        let open = initial_tree(true);
        assert_eq!(open.nodes.len(), 16);
        assert!(open
            .nodes
            .iter()
            .any(|(id, node)| *id == POPOVER_ID && node.role() == Role::Dialog));
    }
}
