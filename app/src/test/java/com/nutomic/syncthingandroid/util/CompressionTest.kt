package com.nutomic.syncthingandroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CompressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `values match the syncthing config strings`() {
        assertEquals("never", Compression.NONE.getValue(context))
        assertEquals("metadata", Compression.METADATA.getValue(context))
        assertEquals("always", Compression.ALWAYS.getValue(context))
    }

    @Test
    fun `from value and from index`() {
        assertEquals(Compression.NONE, Compression.fromValue(context, "never"))
        assertEquals(Compression.METADATA, Compression.fromValue(context, "metadata"))
        assertEquals(Compression.ALWAYS, Compression.fromValue(context, "always"))
        // CHARACTERIZATION: an unknown value maps to index 0, which is NONE, not METADATA.
        assertEquals(Compression.NONE, Compression.fromValue(context, "bogus"))
        assertEquals(Compression.METADATA, Compression.fromIndex(1))
        assertEquals(Compression.METADATA, Compression.fromIndex(42))
    }

    @Test
    fun `titles are localized`() {
        assertEquals(3, Compression.entries.map { it.getTitle(context) }.distinct().size)
    }
}
