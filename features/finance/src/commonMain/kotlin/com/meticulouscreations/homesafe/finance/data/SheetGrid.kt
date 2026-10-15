package com.meticulouscreations.homesafe.finance.data

/** One cell's value as the Sheets API returns it unformatted: a number, text, a boolean, or nothing. */
sealed interface CellValue {
    data class Text(val text: String) : CellValue
    data class Number(val value: Double) : CellValue
    data class Bool(val value: Boolean) : CellValue
    data object Empty : CellValue
}

/** A merged block, 0-based and half-open the way the Sheets API's GridRange is. */
data class MergedRange(val startRow: Int, val endRow: Int, val startColumn: Int, val endColumn: Int) {
    operator fun contains(cell: Pair<Int, Int>): Boolean =
        cell.first in startRow until endRow && cell.second in startColumn until endColumn
}

/**
 * One tab of the workbook. The Sheets API puts a merged block's value in its top-left cell only,
 * so [value] reads every cell of a merge as that value — which is how the budget marks a joint
 * account: one amount merged across both people's columns.
 */
class SheetGrid(val title: String, private val rows: List<List<CellValue>>, private val merges: List<MergedRange>) {
    val rowCount: Int get() = rows.size

    fun columnCount(row: Int): Int = rows.getOrNull(row)?.size ?: 0

    // Every merged cell to its block, so the parser's many whole-grid scans don't each search the
    // merge list for every cell. Blocks are small (a few cells), so this stays tiny.
    private val mergeIndex: Map<Long, MergedRange> = buildMap {
        merges.forEach { m ->
            for (r in m.startRow until m.endRow) for (c in m.startColumn until m.endColumn) put(key(r, c), m)
        }
    }

    private fun key(row: Int, column: Int): Long = (row.toLong() shl 32) or column.toLong()

    fun mergeAt(row: Int, column: Int): MergedRange? = mergeIndex[key(row, column)]

    /** Whether ([row], [column]) is inside a merged block but not its top-left cell: a repeat of the value there. */
    fun isMergeTail(row: Int, column: Int): Boolean = mergeAt(row, column)?.let { it.startRow != row || it.startColumn != column } == true

    fun value(row: Int, column: Int): CellValue {
        val merge = mergeAt(row, column)
        val (r, c) = if (merge != null) merge.startRow to merge.startColumn else row to column
        return rows.getOrNull(r)?.getOrNull(c) ?: CellValue.Empty
    }

    /** The cell's text, trimmed with runs of whitespace (the sheet's line breaks) folded to one space; null if blank or not text. */
    fun text(row: Int, column: Int): String? =
        (value(row, column) as? CellValue.Text)?.text?.let(::normalise)?.takeIf { it.isNotEmpty() }

    fun number(row: Int, column: Int): Double? = when (val v = value(row, column)) {
        is CellValue.Number -> v.value

        // A number typed as text ("$1,234,000") still counts.
        is CellValue.Text -> parseLooseNumber(v.text)

        else -> null
    }

    fun isBlank(row: Int, column: Int): Boolean = when (val v = value(row, column)) {
        CellValue.Empty -> true
        is CellValue.Text -> v.text.isBlank()
        else -> false
    }

    /** The first cell, scanning row by row, whose text satisfies [match]. */
    fun find(rowsFrom: Int = 0, rowsTo: Int = rowCount, columns: IntRange? = null, match: (String) -> Boolean): Pair<Int, Int>? {
        for (r in rowsFrom until minOf(rowsTo, rowCount)) {
            val range = columns ?: (0 until columnCount(r))
            for (c in range) {
                // Only a merge's own top-left cell, so a merged title is found once, where it starts.
                if (isMergeTail(r, c)) continue
                val t = text(r, c) ?: continue
                if (match(t.lowercase())) return r to c
            }
        }
        return null
    }

    /** The cell whose text is [label] (case- and whitespace-insensitive). */
    fun findLabel(label: String, rowsFrom: Int = 0, rowsTo: Int = rowCount, columns: IntRange? = null): Pair<Int, Int>? {
        val want = normalise(label).lowercase()
        return find(rowsFrom, rowsTo, columns) { it == want }
    }

    fun findStartingWith(prefix: String, rowsFrom: Int = 0, rowsTo: Int = rowCount, columns: IntRange? = null): Pair<Int, Int>? {
        val want = normalise(prefix).lowercase()
        return find(rowsFrom, rowsTo, columns) { it.startsWith(want) }
    }

    /** The first number in [row] to the right of [column], looking at most [maxColumns] across. */
    fun numberRightOf(row: Int, column: Int, maxColumns: Int = 4): Double? {
        val merge = mergeAt(row, column)
        val from = (merge?.endColumn ?: (column + 1))
        for (c in from until from + maxColumns) {
            number(row, c)?.let { return it }
        }
        return null
    }

    /**
     * The last number in [row] within [maxColumns] right of [column], for blocks that put a
     * percentage beside each amount ("Cash Deposit | 25.00% | $250,000").
     */
    fun lastNumberRightOf(row: Int, column: Int, maxColumns: Int = 3): Double? {
        val from = mergeAt(row, column)?.endColumn ?: (column + 1)
        return (from until from + maxColumns).reversed().firstNotNullOfOrNull { number(row, it) }
    }

    /** [label]'s value: the number to its right, if the label is anywhere in the grid. */
    fun valueOf(label: String, rowsFrom: Int = 0, rowsTo: Int = rowCount, columns: IntRange? = null, maxColumns: Int = 4): Double? =
        findLabel(label, rowsFrom, rowsTo, columns)?.let { (r, c) -> numberRightOf(r, c, maxColumns) }

    fun contains(label: String): Boolean = findLabel(label) != null

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val NUMBER = Regex("[-+]?\\d*\\.?\\d+")

        fun normalise(text: String): String = text.trim().replace(WHITESPACE, " ")

        /** "$1,234,000", "-$4,321.50", "20.0%" → 1234000, -4321.5, 0.2. Null for anything else. */
        fun parseLooseNumber(text: String): Double? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val percent = t.endsWith("%")
            val cleaned = t.removeSuffix("%").replace("$", "").replace(",", "").replace(" ", "")
            if (cleaned.isEmpty() || !cleaned.matches(NUMBER)) return null
            val v = cleaned.toDoubleOrNull() ?: return null
            return if (percent) v / 100.0 else v
        }
    }
}
