package dev.sebastiano.spectre.sample

/**
 * Shared vocabulary for the headed two-JVM Robot contention proof (#491).
 *
 * The parent test ([HeadedRobotContentionTest]), the forked probe ([HeadedRobotContentionProbe]),
 * and the deterministic verdict cases ([HeadedRobotContentionAnalysisTest]) all read their
 * constants from here so a change to the block size or the alphabet cannot drift between the three.
 */

/** JUnit tag carried by the headed e2e. Mirrored literally in `sample-desktop/build.gradle.kts`. */
internal const val HEADED_ROBOT_TAG: String = "headedRobot"

/** Character typed by the first probe JVM. */
internal const val FIRST_BLOCK_CHARACTER: Char = 'a'

/** Character typed by the second probe JVM. */
internal const val SECOND_BLOCK_CHARACTER: Char = 'b'

/**
 * Characters each probe types in one `typeText` call.
 *
 * `RobotDriver` sets `Robot.autoDelay` to 10ms and `typeText` issues a press and a release per
 * character, so a block occupies the real keyboard for roughly 0.8s. That matters: the whole proof
 * rests on the two JVMs *wanting* the keyboard at the same time, and a burst short enough to finish
 * inside the other process's scheduling jitter would serialise itself. Lower-case letters are
 * deliberate — an upper-case block would add a Shift press/release around every character, which
 * makes a failure harder to read without making contention any more real.
 */
internal const val BLOCK_LENGTH: Int = 40

/**
 * Opening words of the verdict when the two blocks were shredded into each other.
 *
 * Pinned as a constant because it is the string a red proof has to show. "The test failed" is not
 * evidence that this test can detect interleaving; failing *with this sentence* is.
 */
internal const val INTERLEAVED_FAILURE: String =
    "two headed Robot JVMs interleaved their keystrokes"

/** One uninterrupted run of a single character, as observed in the shared text field. */
internal data class TypedRun(val character: Char, val length: Int) {
    override fun toString(): String = "'$character'x$length"
}

/** Collapses adjacent equal characters of [text] into runs, left to right. */
internal fun typedRuns(text: String): List<TypedRun> {
    val runs = mutableListOf<TypedRun>()
    for (character in text) {
        val last = runs.lastOrNull()
        if (last != null && last.character == character) {
            runs[runs.lastIndex] = last.copy(length = last.length + 1)
        } else {
            runs += TypedRun(character, length = 1)
        }
    }
    return runs
}

/**
 * Returns `null` when [text] holds the two blocks back to back in either order, and otherwise says
 * what went wrong — starting with [INTERLEAVED_FAILURE] if, and only if, the two blocks were
 * shredded into each other.
 *
 * Interleaving is separated from short or contaminated content on purpose. Both are failures and
 * both block the tag, but only one of them means the desktop lease stopped being mutually
 * exclusive; reporting a dropped keystroke under the interleaving sentence would send the next
 * reader hunting a coordinator bug that is not there.
 */
internal fun describeContentionFailure(
    text: String,
    firstCharacter: Char,
    secondCharacter: Char,
    blockLength: Int,
): String? {
    val expected =
        "expected '$firstCharacter'x$blockLength and '$secondCharacter'x$blockLength back to " +
            "back in either order"
    val observed = "observed ${typedRuns(text)} in ${quoted(text)}"
    val foreign = text.filterNot { it == firstCharacter || it == secondCharacter }.toSortedSet()
    if (foreign.isNotEmpty()) {
        return "the shared field holds characters neither probe typed " +
            "(${foreign.joinToString()}); $expected, $observed"
    }
    val runs = typedRuns(text)
    if (runs.size > EXPECTED_RUN_COUNT) {
        return "$INTERLEAVED_FAILURE: $expected, $observed"
    }
    if (runs.size < EXPECTED_RUN_COUNT) {
        return "only ${runs.size} of the two blocks reached the shared field; $expected, $observed"
    }
    if (runs.any { it.length != blockLength }) {
        return "both blocks arrived unmixed but incomplete, so keystrokes were dropped between " +
            "the probes and the field; $expected, $observed"
    }
    return null
}

private fun quoted(text: String): String =
    if (text.length <= QUOTED_TEXT_LIMIT) {
        "\"$text\""
    } else {
        "\"${text.take(QUOTED_TEXT_LIMIT)}\"... (${text.length} characters)"
    }

/** One run per probe: any more is interleaving, any fewer means a block never landed. */
private const val EXPECTED_RUN_COUNT: Int = 2

private const val QUOTED_TEXT_LIMIT: Int = 200
