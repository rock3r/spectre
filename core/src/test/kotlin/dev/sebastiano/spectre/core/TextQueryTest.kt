package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextQueryTest {

    @Test
    fun `exact query matches only exact text by default`() {
        val query = TextQuery.exact("Spectre")

        assertTrue(query.matches("Spectre"))
        assertFalse(query.matches("spectre"))
        assertFalse(query.matches("Spectre sample"))
    }

    @Test
    fun `exact query can ignore case`() {
        val query = TextQuery.exact("Spectre", ignoreCase = true)

        assertTrue(query.matches("spectre"))
    }

    @Test
    fun `substring query matches contained text`() {
        val query = TextQuery.substring("ectr")

        assertTrue(query.matches("Spectre"))
        assertFalse(query.matches("Sceptre"))
    }

    @Test
    fun `substring query can ignore case`() {
        val query = TextQuery.substring("ECTR", ignoreCase = true)

        assertTrue(query.matches("Spectre"))
    }

    @Test
    fun `query never matches null text`() {
        val query = TextQuery.substring("Spectre")

        assertFalse(query.matches(null))
    }
}
