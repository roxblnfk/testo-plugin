package com.github.xepozz.testo.tests.console

/** One column of a [MetadataMatrix]. [group] is the `columnGroup` segment (a merged header spans it) or null. */
internal data class MetadataColumn(val group: String?, val name: String)

/**
 * A test's `number` metadata reshaped into a table, from keys of the form `<prefix>.<row>.[columnGroup].<column>`:
 *
 * - `prefix.row.column`       → a plain matrix (rows × columns);
 * - `prefix.row.group.column` → the same, with columns gathered under a spanning `columnGroup` header.
 *
 * [scalars] are the leftover one-segment keys under the prefix (e.g. `bench.iterations`), which have no place in the
 * grid. Built only when the grid is *complete* — every row carries every column — so a bag of unique keys stays a list.
 */
internal class MetadataMatrix(
    val prefix: String,
    val scalars: List<Pair<String, String>>,
    val rows: List<String>,
    val columns: List<MetadataColumn>,
    private val cells: Map<String, String>,
    // Type is kept per cell, not per column, so [typeOf] stays correct after [transposed] swaps the axes: the unit
    // dimension may be the rows in one orientation and the columns in the other.
    private val cellTypes: Map<String, TestoMetadataType>,
) {
    val hasColumnGroups: Boolean = columns.any { it.group != null }

    /** The raw value string as emitted (base unit, no formatting) — what sorting compares. */
    fun value(row: String, column: MetadataColumn): String = cells[cellKey(row, column)] ?: ""

    /** A column's unit: the single type shared by its cells, or [TestoMetadataType.NUMBER] if they disagree. */
    fun typeOf(column: MetadataColumn): TestoMetadataType =
        rows.mapNotNull { cellTypes[cellKey(it, column)] }.distinct().singleOrNull() ?: TestoMetadataType.NUMBER

    /** A row's unit across the columns, or [TestoMetadataType.NUMBER] if they disagree. */
    fun rowType(row: String): TestoMetadataType =
        columns.mapNotNull { cellTypes[cellKey(row, it)] }.distinct().singleOrNull() ?: TestoMetadataType.NUMBER

    /** Whether every cell of a column shares one type — a chart across the rows is comparable only then. */
    fun isColumnUniform(column: MetadataColumn): Boolean =
        rows.mapNotNull { cellTypes[cellKey(it, column)] }.distinct().size == 1

    /** Whether every cell of a row shares one type — a chart across the columns is comparable only then. */
    fun isRowUniform(row: String): Boolean =
        columns.mapNotNull { cellTypes[cellKey(row, it)] }.distinct().size == 1

    /** The value formatted with its column's unit ([formatMetadataValue]) — what the table cell shows. */
    fun displayValue(row: String, column: MetadataColumn): String = formatMetadataValue(value(row, column), typeOf(column))

    /**
     * The matrix with axes swapped: old columns become rows (a grouped column flattened to `group · name`), old rows
     * become columns. Cells and their types travel with the swap, so units follow the metric wherever it lands.
     */
    fun transposed(): MetadataMatrix {
        val label: (MetadataColumn) -> String = { c -> c.group?.let { "$it · ${c.name}" } ?: c.name }
        val newRows = columns.map(label)
        val newColumns = rows.map { MetadataColumn(null, it) }
        val newCells = HashMap<String, String>()
        val newCellTypes = HashMap<String, TestoMetadataType>()
        for (column in columns) for (row in rows) {
            val key = cellKey(label(column), MetadataColumn(null, row))
            newCells[key] = value(row, column)
            cellTypes[cellKey(row, column)]?.let { newCellTypes[key] = it }
        }
        return MetadataMatrix(prefix, scalars, newRows, newColumns, newCells, newCellTypes)
    }

    companion object {
        // NUL because segments may contain spaces, so any printable separator could collide; as the escape, never the
        // raw byte — that turns the source file binary to git.
        internal fun cellKey(row: String, column: MetadataColumn): String =
            "$row\u0000${column.group ?: ""}\u0000${column.name}"
    }
}

/**
 * A numeric value rendered with its unit: `percent` → `8.52%`, `ms` converted to the most readable time unit
 * (`0.000115` → `115 ns`), `bytes` to `B`/`KB`/`MB`/…, plain `number` left unit-less. Converted units keep two decimals
 * (`1.00 MB`, `1.50 ms`); the smallest unit — whole nanoseconds and bytes, where a fraction is meaningless — is a plain
 * integer. Non-numeric or unparseable values are returned unchanged. Display only — sorting uses the raw value, so the
 * unit conversion never affects order.
 */
internal fun formatMetadataValue(raw: String, type: TestoMetadataType): String =
    raw.toDoubleOrNull()?.let { formatMetadataDouble(it, type) } ?: raw

/** [formatMetadataValue] for an already-parsed value — its own best-fit unit (used for chart hovers). */
internal fun formatMetadataDouble(v: Double, type: TestoMetadataType): String = when (type) {
    TestoMetadataType.PERCENT -> "${round2(v)}%"
    TestoMetadataType.MS -> convertUnit(v * 1_000_000.0, TIME_UNITS, unitFor(v * 1_000_000.0, TIME_UNITS))
    TestoMetadataType.BYTES -> convertUnit(v, BYTE_UNITS, unitFor(v, BYTE_UNITS))
    else -> formatMetadataNumber(v)  // NUMBER, and any non-numeric fallthrough
}

/**
 * A fixed formatter for a whole chart's axis and hovers: one unit chosen from the largest value, so every tick and bar
 * reads in the same unit (all `ns`, or all `ms`). Non-convertible types keep their per-value form.
 */
internal fun chartValueFormatter(type: TestoMetadataType, values: List<Double>): (Double) -> String {
    val maxAbs = values.filter { !it.isNaN() }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
    return when (type) {
        TestoMetadataType.PERCENT -> { v -> "${round2(v)}%" }
        TestoMetadataType.MS -> {
            val unit = unitFor(maxAbs * 1_000_000.0, TIME_UNITS)
            ({ v -> convertUnit(v * 1_000_000.0, TIME_UNITS, unit) })
        }
        TestoMetadataType.BYTES -> {
            val unit = unitFor(maxAbs, BYTE_UNITS)
            ({ v -> convertUnit(v, BYTE_UNITS, unit) })
        }
        else -> { v -> formatMetadataNumber(v) }
    }
}

/**
 * A unit-less number: enough significant digits for small values (a `0.000101` must not collapse to "0"), a plain
 * integer for round values, and always a dot — never the locale's comma, which reads as truncation.
 */
internal fun formatMetadataNumber(v: Double): String {
    if (v == 0.0) return "0"
    val abs = kotlin.math.abs(v)
    val decimals = when {
        abs >= 100 -> 0
        abs >= 1 -> 2
        else -> (-kotlin.math.floor(kotlin.math.log10(abs)).toInt() + 2).coerceIn(2, 8)
    }
    val text = String.format(java.util.Locale.US, "%.${decimals}f", v)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

// Base-unit factors: TIME in nanoseconds, BYTE in bytes. The first entry is the smallest (drawn as a whole number).
private val TIME_UNITS = listOf("ns" to 1.0, "µs" to 1_000.0, "ms" to 1_000_000.0, "s" to 1_000_000_000.0)
private val BYTE_UNITS = listOf("B" to 1.0, "KB" to 1024.0, "MB" to 1048576.0, "GB" to 1073741824.0, "TB" to 1099511627776.0)

/** The largest unit whose factor a base-unit magnitude still reaches (else the smallest). */
private fun unitFor(base: Double, units: List<Pair<String, Double>>): Pair<String, Double> =
    units.lastOrNull { kotlin.math.abs(base) >= it.second } ?: units.first()

/** A base-unit value in [unit]: a whole number for the smallest unit (fractions meaningless), else two decimals. */
private fun convertUnit(base: Double, units: List<Pair<String, Double>>, unit: Pair<String, Double>): String {
    val scaled = base / unit.second
    return "${if (unit === units.first()) whole(scaled) else round2(scaled)} ${unit.first}"
}

/** Exactly two decimals, always a dot (never the locale comma). */
private fun round2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

/** A whole number, rounded (for the smallest unit, where a fraction carries no meaning). */
private fun whole(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)

/**
 * Reshapes a `number` group into a [MetadataMatrix], or null when the keys don't form a full grid — mixed key depths, a
 * key too deep, fewer than two rows/columns, or any missing cell. A null tells the caller to fall back to the flat list.
 */
internal fun buildMetadataMatrix(group: MetadataGroup): MetadataMatrix? {
    if (!group.type.isNumeric) return null

    val scalars = mutableListOf<Pair<String, String>>()
    val cellRows = mutableListOf<String>()
    val cellColumns = mutableListOf<MetadataColumn>()
    val cellValues = mutableListOf<String>()
    val cellTypes = mutableListOf<TestoMetadataType>()
    val depths = mutableSetOf<Int>()

    for (entry in group.entries) {
        val rel = if (group.prefix.isEmpty()) entry.name else entry.name.removePrefix("${group.prefix}.")
        val segments = rel.split('.')
        when (segments.size) {
            1 -> scalars += segments[0] to entry.value
            2 -> {
                cellRows += segments[0]; cellColumns += MetadataColumn(null, segments[1])
                cellValues += entry.value; cellTypes += entry.type; depths += 2
            }
            3 -> {
                cellRows += segments[0]; cellColumns += MetadataColumn(segments[1], segments[2])
                cellValues += entry.value; cellTypes += entry.type; depths += 3
            }
            else -> return null
        }
    }

    if (cellRows.isEmpty()) return null      // scalars only — nothing to tabulate
    if (depths.size != 1) return null        // a prefix mixing 2- and 3-segment keys is not a clean grid

    val rows = cellRows.distinct()
    val columns = cellColumns.distinct()
    // At least one dimension must have two entries — a lone 1×1 cell is not worth a table; a 1×N / N×1 strip is
    // (it renders as a table and can be a pie/bar chart).
    if (rows.size < 2 && columns.size < 2) return null

    val cells = HashMap<String, String>()
    val cellTypeByKey = HashMap<String, TestoMetadataType>()
    for (i in cellRows.indices) {
        val key = MetadataMatrix.cellKey(cellRows[i], cellColumns[i])
        cells[key] = cellValues[i]
        cellTypeByKey[key] = cellTypes[i]
    }
    // Completeness: every row must carry every column, or it is a ragged list, not a table.
    for (row in rows) for (column in columns) if (!cells.containsKey(MetadataMatrix.cellKey(row, column))) return null

    return MetadataMatrix(group.prefix, scalars, rows, columns, cells, cellTypeByKey)
}
