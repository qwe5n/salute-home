package com.salutehome.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TvRowsTest {
    private fun row(title: String, progress: Float = -1f, engaged: Long = 0L) =
        TvRows.Row("com.stremio.one", title, "", "content://x", "", progress, engaged)

    @Test
    fun aTitleInTwoChannelsShowsOnce() {
        val rows = TvRows.merge(emptyList(), listOf(row("Black Mirror"), row("Dark"), row("Black Mirror")))
        assertEquals(listOf("Black Mirror", "Dark"), rows.map { it.title })
    }

    @Test
    fun whatIsBeingWatchedIsNotRecommendedToo() {
        val rows = TvRows.merge(listOf(row("Dark", 0.5f, 1L), row("Shogun", 0.2f, 2L)), listOf(row("dark"), row("Fallout")))
        assertEquals(listOf("Shogun", "Dark", "Fallout"), rows.map { it.title })
    }
}
