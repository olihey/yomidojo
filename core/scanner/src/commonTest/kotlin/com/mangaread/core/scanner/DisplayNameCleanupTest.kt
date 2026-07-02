package com.mangaread.core.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayNameCleanupTest {

    @Test
    fun strips_cbz_extension_and_underscores_and_capitalizes() {
        assertEquals("Chaper 1", cleanDisplayName("chaper_1.cbz"))
        assertEquals("Chaper 18.5", cleanDisplayName("chaper_18.5.cbz"))
    }

    @Test
    fun preserves_a_dot_that_is_not_the_extension() {
        // "Vol. 2.cbz" — only the trailing ".cbz" is an extension; the one after "Vol" isn't.
        assertEquals("Vol. 2", cleanDisplayName("Vol. 2.cbz"))
    }

    @Test
    fun folder_names_have_no_extension_to_strip() {
        // IMAGE_DIR chapters are folder names — a stray "." must survive untouched.
        assertEquals("Vol. 01", cleanDisplayName("Vol. 01"))
        assertEquals("Chapter 5", cleanDisplayName("chapter_5"))
    }

    @Test
    fun only_capitalizes_the_first_letter_leaves_the_rest_alone() {
        assertEquals("Toradora! v10", cleanDisplayName("Toradora! v10.cbz"))
    }
}
