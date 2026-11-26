package dev.sebastiano.spectre.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import kotlinx.serialization.json.JsonPrimitive

internal fun CallToolRequest.optionalBoolean(name: String): Boolean? {
    validateKnownArguments()
    val element = arguments?.get(name) ?: return null
    val primitive = element as? JsonPrimitive
    require(primitive != null && !primitive.isString) {
        "MCP tool argument '$name' must be true or false"
    }
    return primitive.content.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("MCP tool argument '$name' must be true or false")
}

internal fun CallToolRequest.validateKnownArguments() {
    val allowed = MCP_ARGUMENT_NAMES[name] ?: return
    val unknown = arguments?.keys?.firstOrNull { it !in allowed } ?: return
    throw IllegalArgumentException("MCP tool '$name' does not accept argument '$unknown'")
}

private val MCP_ARGUMENT_NAMES: Map<String, Set<String>> =
    mapOf(
        "list_processes" to emptySet(),
        "attach" to setOf("pid"),
        "detach" to setOf("session_id"),
        "windows" to setOf("session_id"),
        "tree" to setOf("session_id"),
        "find" to setOf("session_id", "test_tag"),
        "find_text" to setOf("session_id", "text", "exact"),
        "wait_for_node" to setOf("session_id", "tag", "text", "timeout_ms"),
        "wait_until_gone" to setOf("session_id", "tag", "text", "timeout_ms"),
        "wait_for_visual_idle" to setOf("session_id", "timeout_ms"),
        "wait_for_reload_settled" to setOf("session_id", "timeout_ms"),
        "click" to setOf("session_id", "node_key"),
        "double_click" to setOf("session_id", "node_key"),
        "long_click" to setOf("session_id", "node_key", "hold_for_ms"),
        "swipe" to
            setOf(
                "session_id",
                "from_node_key",
                "to_node_key",
                "start_x",
                "start_y",
                "end_x",
                "end_y",
                "steps",
                "duration_ms",
            ),
        "scroll_wheel" to setOf("session_id", "node_key", "wheel_clicks"),
        "press_key" to setOf("session_id", "key_code", "modifiers"),
        "focus_window" to setOf("session_id", "window_index"),
        "type_text" to setOf("session_id", "text"),
        "screenshot" to setOf("session_id", "window_index", "surface_id", "fullscreen"),
        "capture" to
            setOf(
                "session_id",
                "window_index",
                "out_dir",
                "include_image",
                "source",
                "allow_accessibility_enable",
            ),
        "accessibility_tree" to
            setOf(
                "pid",
                "window",
                "session_id",
                "window_index",
                "maximum_depth",
                "maximum_node_count",
                "timeout_ms",
                "allow_accessibility_enable",
            ),
        "record_start" to setOf("session_id", "output_path", "window_index", "fullscreen"),
        "record_stop" to setOf("session_id"),
        "record_status" to setOf("session_id"),
    )
