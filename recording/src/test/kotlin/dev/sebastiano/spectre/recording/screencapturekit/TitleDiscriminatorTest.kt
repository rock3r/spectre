package dev.sebastiano.spectre.recording.screencapturekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleDiscriminatorTest {

    @Test
    fun `discriminator value starts with the documented Spectre prefix`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        assertTrue(
            discriminator.value.startsWith("Spectre/"),
            "Discriminator must use the well-known prefix the helper looks for; got '${discriminator.value}'",
        )
    }

    @Test
    fun `apply suffixes the original title and returns the discriminator`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        val applied = discriminator.apply()

        assertEquals(discriminator.value, applied)
        assertEquals("MyApp ${discriminator.value}", window.title)
    }

    @Test
    fun `apply on a null-titled window still produces a non-blank discriminated title`() {
        // Compose Windows are usually titled, but JFrame and embedded surfaces can have a null
        // title. The discriminator must still produce a window title that contains the
        // suffix — otherwise the helper's window discovery (substring match) can't find it.
        val window = FakeTitledWindow(initialTitle = null)
        val discriminator = TitleDiscriminator(window)

        discriminator.apply()

        assertTrue(window.title?.contains(discriminator.value) == true)
    }

    @Test
    fun `restore returns the title to the captured original`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        discriminator.apply()
        discriminator.restore()

        assertEquals("MyApp", window.title)
    }

    @Test
    fun `restore handles a null original title`() {
        val window = FakeTitledWindow(initialTitle = null)
        val discriminator = TitleDiscriminator(window)

        discriminator.apply()
        discriminator.restore()

        assertNull(window.title, "Original null title must be restored as null, not empty string")
    }

    @Test
    fun `apply is idempotent — second call does not double-suffix`() {
        // Defensive against a user calling apply twice (e.g. recorder retry path). The window
        // title must end up with exactly one Spectre/<id> suffix, not "MyApp Spectre/x Spectre/x".
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        discriminator.apply()
        val titleAfterFirst = window.title
        discriminator.apply()

        assertEquals(titleAfterFirst, window.title)
        assertEquals(1, window.title!!.split("Spectre/").size - 1)
    }

    @Test
    fun `restore is idempotent — second call does not throw or rewrite`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        discriminator.apply()
        discriminator.restore()
        discriminator.restore() // must not throw

        assertEquals("MyApp", window.title)
    }

    @Test
    fun `restore without prior apply is a no-op`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val discriminator = TitleDiscriminator(window)

        discriminator.restore()

        assertEquals("MyApp", window.title, "Title must be untouched when restore precedes apply")
    }

    @Test
    fun `two discriminators on the same window produce different values`() {
        // Different recordings against the same window must use different discriminators so
        // the helper's `--title-contains` filter never matches a stale recording session.
        val window = FakeTitledWindow(initialTitle = "MyApp")

        val a = TitleDiscriminator(window)
        val b = TitleDiscriminator(window)

        assertNotEquals(a.value, b.value)
    }
}
