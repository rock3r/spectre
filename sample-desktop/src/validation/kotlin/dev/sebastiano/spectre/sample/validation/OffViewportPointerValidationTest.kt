package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Live proof for #564: pointer actions on a node scrolled out of its viewport fail fast.
 *
 * An eager `Column(Modifier.verticalScroll(...))` keeps every row in the semantics tree, including
 * rows clipped entirely outside the viewport, whose `boundsInWindow` is empty. Before the fix,
 * Spectre converted those bounds to a 1x1 rectangle at the surface origin, so `click(row)` landed
 * on whatever sat at the window's top-left corner. Here that is a click counter, which must stay
 * at 0.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OffViewportPointerValidationTest {

    private val fixture =
        SampleAppFixture(
            title = "Spectre off-viewport pointer validation",
            content = { OffViewportContent() },
        )

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `pointer actions on an off-viewport node fail instead of hitting the surface origin`() =
        runBlocking {
            with(fixture.automator) {
                val visibleRow = waitForTestTag(FIRST_ROW_TAG)
                val hiddenRow = waitForTestTag(LAST_ROW_TAG)
                assertTrue(
                    hiddenRow.boundsInWindow.isEmpty,
                    "precondition: the last row must be clipped out of the viewport, " +
                        "got ${hiddenRow.boundsInWindow}",
                )

                assertFailsFast("Cannot click") { click(hiddenRow) }
                assertFailsFast("Cannot double-click") { doubleClick(hiddenRow) }
                assertFailsFast("Cannot long-click") {
                    longClick(hiddenRow, holdFor = 50.milliseconds)
                }
                assertFailsFast("Cannot move the pointer to") { moveTo(hiddenRow) }
                assertFailsFast("Cannot scroll the wheel over") {
                    scrollWheel(hiddenRow, wheelClicks = 1)
                }
                assertFailsFast("Cannot swipe from") { swipe(from = hiddenRow, to = visibleRow) }
                assertFailsFast("Cannot swipe to") { swipe(from = visibleRow, to = hiddenRow) }

                assertEquals("originClicks:0", waitForTestTag(ORIGIN_CLICKS_TAG).text)
            }
        }

    private suspend fun assertFailsFast(expectedPrefix: String, action: suspend () -> Unit) {
        val error = assertFailsWith<IllegalStateException>(expectedPrefix) { action() }
        val message = error.message.orEmpty()
        assertTrue(message.startsWith(expectedPrefix), "unexpected message: $message")
        assertTrue("Scroll it into view" in message, "no scroll hint: $message")
    }

    @Test
    @Order(2)
    fun `the same node is actionable once scrolled into view`() = runBlocking {
        with(fixture.automator) {
            val list = waitForTestTag(LIST_TAG)
            val revealed =
                eventually(description = "last row to scroll into the viewport") {
                    scrollWheel(list, wheelClicks = 3)
                    findOneByTestTag(LAST_ROW_TAG)?.takeUnless { it.boundsInWindow.isEmpty }
                }

            moveTo(revealed)

            assertEquals("originClicks:0", waitForTestTag(ORIGIN_CLICKS_TAG).text)
        }
    }
}

private const val ROW_COUNT: Int = 30
private const val LIST_TAG: String = "offViewport.list"
private const val FIRST_ROW_TAG: String = "offViewport.row.0"
private const val LAST_ROW_TAG: String = "offViewport.row.${ROW_COUNT - 1}"
private const val ORIGIN_CLICKS_TAG: String = "offViewport.originClicks"

/**
 * A click counter pinned to the surface origin, above a short eager scroll column whose later rows
 * start out clipped below its viewport.
 */
@Composable
private fun OffViewportContent() {
    var originClicks by remember { mutableIntStateOf(0) }
    Column {
        Box(modifier = Modifier.size(width = 240.dp, height = 60.dp).clickable { originClicks++ }) {
            Text("originClicks:$originClicks", modifier = Modifier.testTag(ORIGIN_CLICKS_TAG))
        }
        Column(
            modifier =
                Modifier.width(240.dp)
                    .height(120.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(LIST_TAG)
        ) {
            repeat(ROW_COUNT) { index ->
                Text(
                    text = "Row $index",
                    modifier = Modifier.height(40.dp).testTag("offViewport.row.$index"),
                )
            }
        }
    }
}
