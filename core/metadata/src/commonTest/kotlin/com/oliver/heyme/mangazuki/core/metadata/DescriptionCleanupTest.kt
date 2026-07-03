package com.oliver.heyme.mangazuki.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DescriptionCleanupTest {

    @Test
    fun strips_tags_and_converts_br_to_newline() {
        val raw = "<p>A story about <b>friendship</b>.<br>It is good.</p>"
        assertEquals("A story about friendship.\nIt is good.", cleanDescription(raw))
    }

    @Test
    fun unescapes_common_entities() {
        assertEquals("Tom & Jerry's \"adventure\"", cleanDescription("Tom &amp; Jerry&#39;s &quot;adventure&quot;"))
    }

    @Test
    fun strips_trailing_source_attribution() {
        val raw = "A great manga about ninjas. (Source: MangaUpdates)"
        assertEquals("A great manga about ninjas.", cleanDescription(raw))
    }

    @Test
    fun collapses_excess_blank_lines() {
        val raw = "First.<br><br><br><br>Second."
        assertEquals("First.\n\nSecond.", cleanDescription(raw))
    }

    @Test
    fun null_input_returns_null() {
        assertNull(cleanDescription(null))
    }

    @Test
    fun blank_after_cleanup_returns_null() {
        assertNull(cleanDescription("(Source: Anilist)"))
    }
}
