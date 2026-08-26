package com.github.xepozz.testo.tests.console

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.ToolTipManager

/** One named data series of a [TestoBarChart]: a value per category (NaN where the cell has no number). */
internal class ChartSeries(val name: String, val values: List<Double>)

/** Whether a [TestoBarChart] draws grouped bars or connected lines. Lines need one shared unit across all series. */
internal enum class ChartMode { BARS, LINES }

/**
 * A hand-drawn bar chart — grouped when given more than one series. Deliberately self-contained (Graphics2D over a
 * plain [JComponent]): the platform's charting APIs are either absent on 252 or `@ApiStatus.Internal`, and a metrics
 * bar chart needs nothing they add. Handles negative values (a zero baseline inside the range) and a legend for the
 * grouped case; long or numerous category labels are drawn at an angle so they don't overlap. Hovering a bar highlights
 * it, prints its value above it, and shows a tooltip.
 */
internal class TestoBarChart(
    private val title: String,
    private val categories: List<String>,
    private val series: List<ChartSeries>,
    // Axis ticks / on-bar labels: one unit for the whole chart. Hover: per (category, series) value, so a mixed-unit
    // chart still shows each bar in its own unit. Both default to a plain number.
    private val axisFormat: (Double) -> String = { formatNumber(it) },
    private val hoverFormat: (Int, Int, Double) -> String = { _, _, v -> formatNumber(v) },
    private val mode: ChartMode = ChartMode.BARS,
) : JComponent() {

    private class Bar(val rect: Rectangle, val category: Int, val series: Int, val tip: String)

    private val bars = ArrayList<Bar>()
    private var hoveredCategory = -1
    private var hoveredSeries = -1

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        ToolTipManager.sharedInstance().registerComponent(this)
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val bar = bars.firstOrNull { it.rect.contains(e.point) }
                val c = bar?.category ?: -1
                val s = bar?.series ?: -1
                if (c != hoveredCategory || s != hoveredSeries) {
                    hoveredCategory = c; hoveredSeries = s; repaint()
                }
            }
        })
    }

    override fun getToolTipText(event: java.awt.event.MouseEvent): String? =
        bars.firstOrNull { it.rect.contains(event.point) }?.tip

    override fun getPreferredSize(): Dimension {
        val width = (categories.size * series.size * JBUI.scale(26)).coerceIn(JBUI.scale(360), JBUI.scale(900))
        return Dimension(width, JBUI.scale(340))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = background
            g2.fillRect(0, 0, width, height)
            paintChart(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintChart(g2: Graphics2D) {
        bars.clear()
        val pad = JBUI.scale(10)
        val fm = g2.fontMetrics
        val fg = UIUtil.getLabelForeground()

        g2.color = fg
        g2.font = font.deriveFont(font.size2D + JBUI.scale(1))
        g2.drawString(title, pad, pad + g2.fontMetrics.ascent)
        val titleBottom = pad + g2.fontMetrics.height
        g2.font = font

        val legendHeight = if (series.size > 1) fm.height + JBUI.scale(4) else 0
        if (series.size > 1) paintLegend(g2, pad, titleBottom, fg)

        // Reserve room at the bottom for angled category labels and at the left for value ticks.
        val angled = categories.size > 6 || categories.any { fm.stringWidth(it) > JBUI.scale(40) }
        val labelBand = if (angled) JBUI.scale(48) else fm.height + JBUI.scale(4)
        val axisWidth = JBUI.scale(52)

        val plotLeft = pad + axisWidth
        val plotTop = titleBottom + legendHeight + JBUI.scale(6)
        val plotRight = width - pad
        val plotBottom = height - pad - labelBand
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        val all = series.flatMap { it.values }.filter { !it.isNaN() }
        if (all.isEmpty()) return
        val dataMax = maxOf(0.0, all.max())
        val dataMin = minOf(0.0, all.min())
        val range = (dataMax - dataMin).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = (plotBottom - (v - dataMin) / range * (plotBottom - plotTop)).toInt()
        val zeroY = yOf(0.0)

        g2.color = gridColor()
        g2.drawLine(plotLeft, plotTop, plotLeft, plotBottom)
        g2.drawLine(plotLeft, zeroY, plotRight, zeroY)
        g2.color = JBColor.GRAY
        for (t in 0..2) {
            val v = dataMin + range * t / 2
            val y = yOf(v)
            g2.drawString(axisFormat(v), pad, y + fm.ascent / 2)
        }

        val slot = (plotRight - plotLeft).toDouble() / categories.size
        if (mode == ChartMode.BARS) paintBars(g2, ::yOf, zeroY, plotLeft, slot, fm, fg)
        else paintLines(g2, ::yOf, plotLeft, slot, fm, fg)

        for (c in categories.indices) {
            val center = (plotLeft + slot * c + slot / 2).toInt()
            paintCategoryLabel(g2, categories[c], center, plotBottom, angled, fg, fm)
        }
    }

    private fun paintBars(
        g2: Graphics2D, yOf: (Double) -> Int, zeroY: Int, plotLeft: Int, slot: Double,
        fm: java.awt.FontMetrics, fg: Color,
    ) {
        val groupGap = slot * 0.2
        val barSlot = (slot - groupGap) / series.size
        val barWidth = (barSlot * 0.85).toInt().coerceAtLeast(2)
        for (c in categories.indices) {
            val slotLeft = plotLeft + slot * c + groupGap / 2
            for (s in series.indices) {
                val v = series[s].values.getOrNull(c) ?: Double.NaN
                if (v.isNaN()) continue
                val x = (slotLeft + barSlot * s).toInt()
                val y = yOf(v)
                val top = minOf(y, zeroY)
                val h = kotlin.math.abs(y - zeroY).coerceAtLeast(1)
                val hovered = c == hoveredCategory && s == hoveredSeries
                g2.color = if (hovered) seriesColor(s).brighter() else seriesColor(s)
                g2.fillRect(x, top, barWidth, h)
                if (hovered) drawValueLabel(g2, axisFormat(v), x + barWidth / 2, if (v >= 0) top else top + h, fg, fm)
                bars += Bar(Rectangle(x, top, barWidth, h), c, s, tip(c, s, v))
            }
        }
    }

    private fun paintLines(
        g2: Graphics2D, yOf: (Double) -> Int, plotLeft: Int, slot: Double, fm: java.awt.FontMetrics, fg: Color,
    ) {
        val dot = JBUI.scale(4)
        fun centerX(c: Int) = (plotLeft + slot * c + slot / 2).toInt()
        for (s in series.indices) {
            g2.color = seriesColor(s)
            g2.stroke = java.awt.BasicStroke(com.intellij.ui.scale.JBUIScale.scale(1.5f))
            var prevX = -1
            var prevY = 0
            for (c in categories.indices) {
                val v = series[s].values.getOrNull(c) ?: Double.NaN
                if (v.isNaN()) { prevX = -1; continue }
                val x = centerX(c)
                val y = yOf(v)
                if (prevX >= 0) g2.drawLine(prevX, prevY, x, y)
                prevX = x; prevY = y
            }
        }
        // Dots and hit regions on top of the lines, so a hovered point sits above every stroke.
        for (s in series.indices) {
            for (c in categories.indices) {
                val v = series[s].values.getOrNull(c) ?: Double.NaN
                if (v.isNaN()) continue
                val x = centerX(c)
                val y = yOf(v)
                val hovered = c == hoveredCategory && s == hoveredSeries
                val r = if (hovered) dot + JBUI.scale(2) else dot
                g2.color = if (hovered) seriesColor(s).brighter() else seriesColor(s)
                g2.fillOval(x - r, y - r, r * 2, r * 2)
                if (hovered) drawValueLabel(g2, axisFormat(v), x, y - r, fg, fm)
                bars += Bar(Rectangle(x - dot - JBUI.scale(3), y - dot - JBUI.scale(3), (dot + JBUI.scale(3)) * 2, (dot + JBUI.scale(3)) * 2), c, s, tip(c, s, v))
            }
        }
    }

    private fun drawValueLabel(g2: Graphics2D, label: String, centerX: Int, aboveY: Int, fg: Color, fm: java.awt.FontMetrics) {
        g2.color = fg
        g2.drawString(label, centerX - fm.stringWidth(label) / 2, aboveY - JBUI.scale(2))
    }

    private fun tip(c: Int, s: Int, v: Double): String = "${series[s].name} · ${categories[c]}: ${hoverFormat(c, s, v)}"

    private fun paintCategoryLabel(
        g2: Graphics2D, label: String, centerX: Int, baselineY: Int, angled: Boolean, fg: Color,
        fm: java.awt.FontMetrics,
    ) {
        g2.color = fg
        if (angled) {
            val old = g2.transform
            g2.translate(centerX.toDouble(), (baselineY + JBUI.scale(4)).toDouble())
            g2.rotate(Math.toRadians(35.0))
            g2.drawString(label, 0, fm.ascent)
            g2.transform = old
        } else {
            g2.drawString(label, centerX - fm.stringWidth(label) / 2, baselineY + fm.ascent + JBUI.scale(2))
        }
    }

    private fun paintLegend(g2: Graphics2D, left: Int, top: Int, fg: Color) {
        val fm = g2.fontMetrics
        var x = left
        val y = top + JBUI.scale(2)
        val box = fm.ascent
        for (s in series.indices) {
            g2.color = seriesColor(s)
            g2.fill(Rectangle2D.Float(x.toFloat(), y.toFloat(), box.toFloat(), box.toFloat()))
            g2.color = fg
            g2.drawString(series[s].name, x + box + JBUI.scale(4), y + fm.ascent)
            x += box + JBUI.scale(6) + fm.stringWidth(series[s].name) + JBUI.scale(12)
        }
    }

    private fun gridColor() = JBColor.border()

    private fun seriesColor(index: Int): Color = PALETTE[index % PALETTE.size]

    companion object {
        private val PALETTE: List<Color> = listOf(
            JBColor(0x3592C4, 0x3592C4),
            JBColor(0x59A869, 0x59A869),
            JBColor(0xCC7832, 0xCC7832),
            JBColor(0xB95EAA, 0xB95EAA),
            JBColor(0xC8A415, 0xC8A415),
            JBColor(0x42A4A4, 0x42A4A4),
        )

        // Enough significant digits for small metrics (a `ms` value of 0.000101 must not collapse to "0.00"), a plain
        // integer for round values, and always a dot — never the locale's comma, which read as truncation.
        internal fun formatNumber(v: Double): String {
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
    }
}
