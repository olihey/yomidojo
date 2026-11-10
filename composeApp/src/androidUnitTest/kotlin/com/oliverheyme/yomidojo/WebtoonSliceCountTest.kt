package com.oliverheyme.yomidojo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebtoonSliceCountTest {

    @Test
    fun normal_and_moderately_tall_pages_stay_whole() {
        assertEquals(1, webtoonSliceCount(1800f))
        assertEquals(1, webtoonSliceCount(MAX_UNSLICED_PAGE_PX.toFloat()))
    }

    @Test
    fun a_stitched_strip_is_cut_into_bands_no_taller_than_the_slice_target() {
        val height = 47_897f // "We Don't Need a Prince" ch. 1: one 690 x 47897 PNG
        val count = webtoonSliceCount(height)
        assertEquals(12, count)
        assertTrue(height / count <= PAGE_SLICE_PX)
    }

    @Test
    fun just_over_the_limit_splits_into_bands_under_the_target() {
        val height = MAX_UNSLICED_PAGE_PX + 1f
        assertEquals(3, webtoonSliceCount(height))
    }
}
