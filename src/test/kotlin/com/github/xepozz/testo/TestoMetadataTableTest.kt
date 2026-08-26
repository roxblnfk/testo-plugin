package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.MetadataColumn
import com.github.xepozz.testo.tests.console.TestoMetadataEntry
import com.github.xepozz.testo.tests.console.TestoMetadataType
import com.github.xepozz.testo.tests.console.buildMetadataMatrix
import com.github.xepozz.testo.tests.console.formatMetadata
import com.github.xepozz.testo.tests.console.formatMetadataValue
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
    fun formatsValuesWithUnits() {
        // Smallest unit (ns / B) is a whole number; larger converted units keep two decimals.
        assertEquals("115 ns", formatMetadataValue("0.000115", TestoMetadataType.MS))
        assertEquals("1.50 µs", formatMetadataValue("0.0015", TestoMetadataType.MS))
        assertEquals("1.50 ms", formatMetadataValue("1.5", TestoMetadataType.MS))
        assertEquals("8.52%", formatMetadataValue("8.517154", TestoMetadataType.PERCENT))
        assertEquals("0.00%", formatMetadataValue("0", TestoMetadataType.PERCENT))
        assertEquals("1.00 MB", formatMetadataValue("1048576", TestoMetadataType.BYTES))
        assertEquals("1.50 KB", formatMetadataValue("1536", TestoMetadataType.BYTES))
        assertEquals("512 B", formatMetadataValue("512", TestoMetadataType.BYTES))
        assertEquals("0 B", formatMetadataValue("0", TestoMetadataType.BYTES))
        // Unit-less number: trailing zeros trimmed, small values keep their significant digits.
        assertEquals("20", formatMetadataValue("20", TestoMetadataType.NUMBER))
        assertEquals("0.0005", formatMetadataValue("0.0005", TestoMetadataType.NUMBER))
        // Unparseable / non-numeric passes through untouched.
        assertEquals("n/a", formatMetadataValue("n/a", TestoMetadataType.MS))
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
    fun transposeSwapsAxesAndCarriesUnits() {
        // Metric-first keys (`bench.<metric>.<variant>`): the unit dimension is the row, so the direct columns
        // (variants) mix units and read as NUMBER — transposing makes the metric the column and restores its unit.
        val group = groupMetadata(
            listOf(
                TestoMetadataEntry("bench.mean.current", TestoMetadataType.MS, "0.001"),
                TestoMetadataEntry("bench.calls.current", TestoMetadataType.NUMBER, "20"),
                TestoMetadataEntry("bench.mean.division", TestoMetadataType.MS, "0.002"),
                TestoMetadataEntry("bench.calls.division", TestoMetadataType.NUMBER, "20"),
            )
        ).single()
        val direct = buildMetadataMatrix(group)!!
        assertEquals(listOf("mean", "calls"), direct.rows)
        assertEquals(TestoMetadataType.NUMBER, direct.typeOf(MetadataColumn(null, "current")))  // mixed → NUMBER

        val t = direct.transposed()
        assertEquals(listOf("current", "division"), t.rows)
        assertEquals(listOf(MetadataColumn(null, "mean"), MetadataColumn(null, "calls")), t.columns)
        assertEquals("0.002", t.value("division", MetadataColumn(null, "mean")))
        assertEquals(TestoMetadataType.MS, t.typeOf(MetadataColumn(null, "mean")))
        assertEquals("2.00 µs", t.displayValue("division", MetadataColumn(null, "mean")))
    }

    @Test
    fun detectsRowAndColumnUniformity() {
        // rows current/division; columns mean(ms) and calls(number). Columns are uniform, rows are mixed.
        val group = groupMetadata(
            listOf(
                TestoMetadataEntry("bench.current.mean", TestoMetadataType.MS, "0.001"),
                TestoMetadataEntry("bench.current.calls", TestoMetadataType.NUMBER, "20"),
                TestoMetadataEntry("bench.division.mean", TestoMetadataType.MS, "0.002"),
                TestoMetadataEntry("bench.division.calls", TestoMetadataType.NUMBER, "20"),
            )
        ).single()
        val matrix = buildMetadataMatrix(group)!!
        assertTrue(matrix.isColumnUniform(MetadataColumn(null, "mean")))
        assertTrue(!matrix.isRowUniform("current"))
        assertEquals(TestoMetadataType.MS, matrix.typeOf(MetadataColumn(null, "mean")))
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
    fun singleColumnBuildsOneDimensionalMatrix() {
        // A single column across ≥2 rows is a valid 1×N strip (a table, and a pie/bar candidate).
        val group = groupMetadata(listOf(n("t.rowA.only", "1"), n("t.rowB.only", "2"))).single()
        val matrix = buildMetadataMatrix(group)!!
        assertEquals(listOf("rowA", "rowB"), matrix.rows)
        assertEquals(listOf(MetadataColumn(null, "only")), matrix.columns)
    }

    @Test
    fun singleCellIsNotATable() {
        val group = groupMetadata(listOf(n("t.rowA.only", "1"))).single()
        assertNull(buildMetadataMatrix(group))
    }

    @Test
    fun unitNumericTypesAreTreatedAsNumbers() {
        assertTrue(TestoMetadataType.MS.isNumeric)
        assertTrue(TestoMetadataType.BYTES.isNumeric)
        assertTrue(TestoMetadataType.PERCENT.isNumeric)
        assertFalse(TestoMetadataType.TEXT.isNumeric)
        assertEquals(TestoMetadataType.PERCENT, TestoMetadataType.fromWire("percent"))

        // A `ms` grid tabulates the same as a `number` one.
        val group = groupMetadata(
            listOf(
                TestoMetadataEntry("t.a.x", TestoMetadataType.MS, "1"),
                TestoMetadataEntry("t.a.y", TestoMetadataType.MS, "2"),
                TestoMetadataEntry("t.b.x", TestoMetadataType.MS, "3"),
                TestoMetadataEntry("t.b.y", TestoMetadataType.MS, "4"),
            )
        ).single()
        val matrix = buildMetadataMatrix(group)!!
        assertEquals(listOf("a", "b"), matrix.rows)
    }

    @Test
    fun mixedNumericTypesStayInOneGroup() {
        // number + ms + percent + bytes under one prefix must pool into a single numeric group, not four cards.
        val groups = groupMetadata(
            listOf(
                n("bench.current.Setup.calls", "20"),
                TestoMetadataEntry("bench.current.Time.mean", TestoMetadataType.MS, "0.1"),
                TestoMetadataEntry("bench.current.Time.diff", TestoMetadataType.PERCENT, "0"),
                TestoMetadataEntry("bench.current.Summary.memory", TestoMetadataType.BYTES, "0"),
                n("bench.division.Setup.calls", "20"),
                TestoMetadataEntry("bench.division.Time.mean", TestoMetadataType.MS, "0.11"),
                TestoMetadataEntry("bench.division.Time.diff", TestoMetadataType.PERCENT, "8.4"),
                TestoMetadataEntry("bench.division.Summary.memory", TestoMetadataType.BYTES, "0"),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(TestoMetadataType.NUMBER, groups[0].type)

        val matrix = buildMetadataMatrix(groups[0])!!
        assertEquals(listOf("current", "division"), matrix.rows)
        assertTrue(matrix.hasColumnGroups)
        assertEquals("0.11", matrix.value("division", MetadataColumn("Time", "mean")))
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
