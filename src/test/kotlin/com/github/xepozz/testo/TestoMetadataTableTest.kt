package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.MetadataColumn
import com.github.xepozz.testo.tests.console.TestoMetadataEntry
import com.github.xepozz.testo.tests.console.TestoMetadataType
import com.github.xepozz.testo.tests.console.buildMetadataMatrix
import com.github.xepozz.testo.tests.console.formatMetadata
import com.github.xepozz.testo.tests.console.groupMetadata
import com.github.xepozz.testo.tests.console.isMetadataUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit4 tests for the metadata grouping / table-shaping logic — no IDE platform dependencies. */
class TestoMetadataTableTest {

    private fun n(name: String, value: String) = TestoMetadataEntry(name, TestoMetadataType.NUMBER, value)

    @Test
    fun groupsByPrefixAndType() {
        val groups = groupMetadata(
            listOf(
                n("bench.iterations", "10"),
                n("bench.current.calls", "20"),
                TestoMetadataEntry("http.url", TestoMetadataType.LINK, "http://x"),
            )
        )
        assertEquals(2, groups.size)
        assertEquals("bench" to TestoMetadataType.NUMBER, groups[0].prefix to groups[0].type)
        assertEquals("http" to TestoMetadataType.LINK, groups[1].prefix to groups[1].type)
        assertEquals(2, groups[0].entries.size)
    }

    @Test
    fun formatDropsSharedPrefix() {
        val group = groupMetadata(listOf(n("bench.current.calls", "20"), n("bench.iterations", "10"))).single()
        assertEquals("current.calls = 20\niterations = 10", formatMetadata(group))
    }

    @Test
    fun formatKeepsLoneDottedName() {
        // A single `report.csv`-style key must not be shortened to `csv` — its dot is not a shared prefix.
        val group = groupMetadata(
            listOf(TestoMetadataEntry("measurements.csv", TestoMetadataType.ARTIFACT, "/tmp/report.csv"))
        ).single()
        assertEquals("measurements.csv = /tmp/report.csv", formatMetadata(group))
    }

    @Test
    fun detectsUrls() {
        assertTrue(isMetadataUrl("https://php-testo.github.io/"))
        assertTrue(isMetadataUrl("HTTP://example.com"))
        assertFalse(isMetadataUrl("D:\\git\\testo\\resources\\chart.png"))
        assertFalse(isMetadataUrl("/tmp/report.csv"))
    }

    @Test
    fun buildsCompleteMatrix() {
        val group = groupMetadata(
            listOf(
                n("bench.iterations", "10"),
                n("bench.current.calls", "20"),
                n("bench.current.place", "3"),
                n("bench.division.calls", "20"),
                n("bench.division.place", "1"),
            )
        ).single()

        val matrix = buildMetadataMatrix(group)!!
        assertEquals(listOf("current", "division"), matrix.rows)
        assertEquals(listOf(MetadataColumn(null, "calls"), MetadataColumn(null, "place")), matrix.columns)
        assertEquals(listOf("iterations" to "10"), matrix.scalars)
        assertEquals("1", matrix.value("division", MetadataColumn(null, "place")))
        assertTrue(!matrix.hasColumnGroups)
    }

    @Test
    fun buildsColumnGroupedMatrix() {
        val group = groupMetadata(
            listOf(
                n("t.rowA.g1.x", "1"),
                n("t.rowA.g1.y", "2"),
                n("t.rowB.g1.x", "3"),
                n("t.rowB.g1.y", "4"),
            )
        ).single()

        val matrix = buildMetadataMatrix(group)!!
        assertTrue(matrix.hasColumnGroups)
        assertEquals(listOf(MetadataColumn("g1", "x"), MetadataColumn("g1", "y")), matrix.columns)
        assertEquals("4", matrix.value("rowB", MetadataColumn("g1", "y")))
    }

    @Test
    fun raggedMatrixFallsBackToList() {
        // rowB is missing the `place` cell — not a complete grid.
        val group = groupMetadata(
            listOf(
                n("bench.current.calls", "20"),
                n("bench.current.place", "3"),
                n("bench.division.calls", "20"),
            )
        ).single()
        assertNull(buildMetadataMatrix(group))
    }

    @Test
    fun mixedKeyDepthsFallBackToList() {
        val group = groupMetadata(
            listOf(
                n("t.rowA.x", "1"),
                n("t.rowA.g.y", "2"),
                n("t.rowB.x", "3"),
                n("t.rowB.g.y", "4"),
            )
        ).single()
        assertNull(buildMetadataMatrix(group))
    }

    @Test
    fun singleColumnIsNotATable() {
        val group = groupMetadata(listOf(n("t.rowA.only", "1"), n("t.rowB.only", "2"))).single()
        assertNull(buildMetadataMatrix(group))
    }

    @Test
    fun nonNumberGroupIsNeverATable() {
        val group = groupMetadata(
            listOf(
                TestoMetadataEntry("t.a.x", TestoMetadataType.TEXT, "1"),
                TestoMetadataEntry("t.a.y", TestoMetadataType.TEXT, "2"),
                TestoMetadataEntry("t.b.x", TestoMetadataType.TEXT, "3"),
                TestoMetadataEntry("t.b.y", TestoMetadataType.TEXT, "4"),
            )
        ).single()
        assertNull(buildMetadataMatrix(group))
    }
}
