package dev.sebastiano.spectre.cli.guide

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `spectre guide`: prints the user guide that the build bundles into the CLI, so the installed CLI
 * always carries the guide for its own version. Works offline and never contacts the daemon.
 */
internal class GuideCommand(private val output: Appendable) : CliktCommand(name = "guide") {
    private val words: List<String> by
        argument(
                name = "TOPIC",
                help = "Topic to print, e.g. traps or cli. With --search, more search words.",
            )
            .multiple()
    private val search: String? by
        option(
            "--search",
            "-s",
            help =
                "Search every topic and print the best sections with their topic and headings. " +
                    "Describe the symptom, e.g. --search \"xvfb-run hangs\".",
        )
    private val limit: Int by
        option("-n", "--limit", help = "Number of search results (default $DEFAULT_LIMIT).")
            .int()
            .restrictTo(1..MAX_LIMIT)
            .default(DEFAULT_LIMIT)
    private val json: Boolean by option("--json").flag(default = false)

    override fun help(context: Context): String =
        "Print the bundled Spectre user guide: the CLI workflow and topic list, one topic, " +
            "or the sections that best match a search. Works offline."

    override fun run() {
        val query = search
        when {
            query != null -> printSearch((listOf(query) + words).joinToString(" ").trim())
            words.isEmpty() -> printOverview()
            words.size == 1 -> printTopic(words.single())
            else ->
                throw UsageError(
                    "guide takes one topic. To search, run: " +
                        "spectre guide --search \"${words.joinToString(" ")}\""
                )
        }
    }

    private fun printOverview() {
        val text = overviewText()
        if (json) emit(GuideOverviewJson(text = text, topics = GUIDE_TOPICS.map(::GuideTopicJson)))
        else output.append(text)
    }

    private fun printTopic(name: String) {
        val topic =
            resolveGuideTopic(name)
                ?: throw UsageError(
                    "unknown guide topic \"$name\". Topics: " +
                        GUIDE_TOPICS.joinToString(", ") { it.name } +
                        ". To search instead, run: spectre guide --search \"$name\""
                )
        val markdown =
            readGuideTopic(topic.name)
                ?: error("The Spectre CLI does not bundle the ${topic.name} guide topic")
        val text = stripAliases(markdown)
        if (json) emit(GuideTopicTextJson(topic = topic.name, text = text))
        else output.append(text.trimEnd('\n')).appendLine()
    }

    private fun printSearch(query: String) {
        if (searchTokens(query).isEmpty()) {
            throw UsageError(
                "usage: spectre guide --search <words>, e.g. spectre guide --search \"xvfb-run hangs\""
            )
        }
        val capped = capHits(guideIndex.search(query, limit), PER_HIT_BYTES, TOTAL_SEARCH_BYTES)
        if (json) {
            emit(
                GuideSearchJson(
                    query = query,
                    results = capped.hits.map(::GuideHitJson),
                    omitted = capped.omitted,
                )
            )
        } else {
            output.append(searchText(capped))
        }
    }

    private inline fun <reified T> emit(value: T) {
        output.append(GUIDE_JSON.encodeToString(value)).appendLine()
    }
}

private fun searchText(capped: CappedHits): String = buildString {
    if (capped.hits.isEmpty()) {
        appendLine(
            "No guide section matches. Topics: ${GUIDE_TOPICS.joinToString(", ") { it.name }}."
        )
        appendLine("Try other words, or read a topic with: spectre guide <topic>")
        return@buildString
    }
    for (hit in capped.hits) {
        appendLine("== ${hit.section.breadcrumb}  (spectre guide ${hit.section.topic})")
        appendLine(hit.text)
        appendLine()
    }
    if (capped.omitted > 0) {
        appendLine("(${capped.omitted} more sections did not fit; search with more specific words)")
    }
}

private fun overviewText(): String = buildString {
    appendLine("Spectre drives live Compose Desktop UIs: semantics, input, captures, and video.")
    appendLine("The CLI attaches to a running JVM. The target needs no build or classpath change.")
    appendLine()
    appendLine("CLI workflow:")
    WORKFLOW.forEach { (command, purpose) ->
        appendLine("  ${command.padEnd(WORKFLOW_PAD)} $purpose")
    }
    appendLine()
    appendLine("Read \"spectre guide traps\" before your first session.")
    appendLine()
    appendLine("Topics (spectre guide <topic>):")
    val width = GUIDE_TOPICS.maxOf { it.name.length }
    GUIDE_TOPICS.forEach { topic -> appendLine("  ${topic.name.padEnd(width)}  ${topic.summary}") }
    appendLine()
    appendLine("Search every topic: spectre guide --search \"<symptom or words>\" [-n 3] [--json]")
}

private val WORKFLOW: List<Pair<String, String>> =
    listOf(
        "spectre ps --json" to "list attachable JVMs (same OS user)",
        "spectre attach <pid> --json" to "attach and print a session id",
        "spectre windows <session-id>" to "list windows and popups",
        "spectre tree <session-id> --json" to "read the semantics tree and node keys",
        "spectre find <session-id> <test-tag>" to "find nodes by test tag (find-text for text)",
        "spectre click <session-id> <node-key>" to "click; also double-click, long-click, swipe",
        "spectre type <session-id> <text>" to "type into the focused node; click the field first",
        "spectre press-key <session-id> <code>" to "send an AWT key code, e.g. 10 for Enter",
        "spectre capture <session-id> --json" to "window PNG plus capture.json",
        "spectre record start <session-id>" to "record window video; stop with record stop",
        "spectre detach <session-id>" to "end the session",
    )

@Serializable
private data class GuideTopicJson(val name: String, val summary: String) {
    constructor(topic: GuideTopic) : this(topic.name, topic.summary)
}

@Serializable
private data class GuideOverviewJson(
    val version: Int = JSON_VERSION,
    val text: String,
    val topics: List<GuideTopicJson>,
)

@Serializable
private data class GuideTopicTextJson(
    val version: Int = JSON_VERSION,
    val topic: String,
    val text: String,
)

@Serializable
private data class GuideHitJson(
    val topic: String,
    val path: List<String>,
    val breadcrumb: String,
    val text: String,
    val score: Double,
    val truncated: Boolean,
) {
    constructor(
        hit: GuideHit
    ) : this(
        topic = hit.section.topic,
        path = hit.section.path,
        breadcrumb = hit.section.breadcrumb,
        text = hit.text,
        score = hit.score,
        truncated = hit.truncated,
    )
}

@Serializable
private data class GuideSearchJson(
    val version: Int = JSON_VERSION,
    val query: String,
    val results: List<GuideHitJson>,
    val omitted: Int,
)

private val GUIDE_JSON: Json = Json { encodeDefaults = true }
private const val JSON_VERSION: Int = 1
private const val DEFAULT_LIMIT: Int = 3
private const val MAX_LIMIT: Int = 20
private const val PER_HIT_BYTES: Int = 1_500
private const val TOTAL_SEARCH_BYTES: Int = 4_000
private const val WORKFLOW_PAD: Int = 38
