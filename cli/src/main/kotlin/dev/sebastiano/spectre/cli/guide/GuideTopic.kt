package dev.sebastiano.spectre.cli.guide

/** One bundled user-guide page. The build copies `docs/guide/<name>.md` into the CLI. */
internal data class GuideTopic(val name: String, val summary: String)

/**
 * The bundled topics, in the order `spectre guide` lists them. A test checks that this list matches
 * the pages in `docs/guide/`, so a new page needs an entry here.
 */
internal val GUIDE_TOPICS: List<GuideTopic> =
    listOf(
        GuideTopic("cli", "Every CLI command, session lifetime, capture modes, and the MCP server"),
        GuideTopic("traps", "Mistakes that cost agents and developers time, and how to avoid them"),
        GuideTopic("agent", "Attach to a running JVM, with or without spectre-core in the target"),
        GuideTopic("capture", "Atomic capture: window PNG plus capture.json, jq recipes, cleanup"),
        GuideTopic("hot-reload", "Compose Hot Reload settle waits and node-key invalidation"),
        GuideTopic("troubleshooting", "Symptoms, causes, and fixes per platform"),
        GuideTopic("installation", "Maven coordinates and modules"),
        GuideTopic("getting-started", "Write a first JUnit test against a real Compose window"),
        GuideTopic("automator", "ComposeAutomator: surfaces, the semantics tree, no auto-wait"),
        GuideTopic("selectors", "Find nodes by test tag, text, content description, or role"),
        GuideTopic(
            "interactions",
            "Clicks, swipes, scrolling, typing, keys, and RobotDriver modes",
        ),
        GuideTopic("synchronization", "Wait helpers, idling resources, and the EDT rule"),
        GuideTopic("junit", "JUnit 5 extension, JUnit 4 rule, and screenshot golds"),
        GuideTopic("ci", "JVM flags, xvfb, macOS helper JVMs, and recording on CI"),
        GuideTopic("recording", "Video recording and native window screenshots per platform"),
        GuideTopic("input-coordination", "Experimental real-input leases across JVMs"),
        GuideTopic("cross-jvm", "Drive a UI in another JVM over the experimental HTTP transport"),
        GuideTopic("intellij", "Automate Compose and Jewel UIs hosted in IntelliJ"),
        GuideTopic("capability-matrix", "Operations by transport and platform"),
    )

/** Other names for topics, so `spectre guide gotchas` prints the traps page. */
internal val GUIDE_TOPIC_ALIASES: Map<String, String> = mapOf("gotchas" to "traps")

private const val GUIDE_RESOURCE_DIRECTORY = "spectre-guide"

/** Reads the bundled Markdown of [topic], or returns null when the CLI does not bundle it. */
internal fun readGuideTopic(topic: String): String? =
    GuideTopic::class
        .java
        .classLoader
        .getResourceAsStream("$GUIDE_RESOURCE_DIRECTORY/$topic.md")
        ?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

/** Resolves a topic name or alias, ignoring case. */
internal fun resolveGuideTopic(name: String): GuideTopic? {
    val lowered = name.lowercase()
    val canonical = GUIDE_TOPIC_ALIASES[lowered] ?: lowered
    return GUIDE_TOPICS.firstOrNull { it.name == canonical }
}

/** The search index over every bundled topic, built on first use. */
internal val guideIndex: GuideIndex by lazy {
    GuideIndex(
        GUIDE_TOPICS.flatMap { topic ->
            val markdown =
                readGuideTopic(topic.name)
                    ?: error("The Spectre CLI does not bundle the ${topic.name} guide topic")
            splitGuide(topic.name, markdown)
        }
    )
}
