package dev.adamko.kafkatorio.webmap.production

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent


data class ChartSeries(
  val label: String,
  val colour: String,
  val values: List<Double>,
)


/**
 * A line chart drawn as SVG: a recessive grid, 2px lines, and a crosshair that shows every
 * series' value at the hovered point.
 */
class LineChart(
  private val root: HTMLElement,
  /** e.g. `/min` */
  private val unit: String,
) {

  private var series: List<ChartSeries> = emptyList()
  private var xLabels: List<String> = emptyList()

  private val tooltip = (document.createElement("div") as HTMLElement).apply {
    className = "chart-tooltip"
    style.display = "none"
  }


  fun render(series: List<ChartSeries>, xLabels: List<String>) {
    this.series = series
    this.xLabels = xLabels
    redraw()
  }


  fun redraw() {
    root.innerHTML = ""
    root.appendChild(tooltip)
    tooltip.style.display = "none"

    val width = max(root.clientWidth, 300).toDouble()
    val height = HEIGHT
    val points = xLabels.size
    if (series.isEmpty() || points == 0) {
      val empty = document.createElement("div")
      empty.className = "chart-empty"
      empty.textContent = "Select items in the table below to chart them."
      root.appendChild(empty)
      return
    }

    // direct labels at the end of each line, when there are few enough to fit
    val directLabels = series.size <= 4
    val right = if (directLabels) 120.0 else 16.0
    val plotWidth = width - LEFT - right
    val plotHeight = height - TOP - BOTTOM

    val maxValue = series.maxOf { s -> s.values.maxOrNull() ?: 0.0 }
    val ticks = niceTicks(maxValue)
    val yMax = ticks.last()

    fun x(i: Int) = LEFT + if (points == 1) plotWidth / 2 else plotWidth * i / (points - 1)
    fun y(value: Double) = TOP + plotHeight * (1 - value / yMax)

    val svg = svg("svg", "width" to width, "height" to height, "class" to "line-chart")

    // grid and y axis labels
    ticks.forEach { tick ->
      svg.add("line", "x1" to LEFT, "x2" to LEFT + plotWidth, "y1" to y(tick), "y2" to y(tick), "class" to "chart-grid")
      svg.add("text", "x" to LEFT - 8, "y" to y(tick) + 4, "class" to "chart-axis-label", "text-anchor" to "end")
        .textContent = formatRate(tick)
    }

    // x axis labels: the first, middle and last
    listOf(0, (points - 1) / 2, points - 1).distinct().forEach { i ->
      val anchor = when (i) {
        0          -> "start"
        points - 1 -> "end"
        else       -> "middle"
      }
      svg.add("text", "x" to x(i), "y" to height - 6, "class" to "chart-axis-label", "text-anchor" to anchor)
        .textContent = xLabels[i]
    }

    // lines
    series.forEach { s ->
      val path = s.values.mapIndexed { i, v -> "${if (i == 0) "M" else "L"}${x(i).fmt()},${y(v).fmt()}" }.joinToString("")
      svg.add("path", "d" to path, "class" to "chart-line", "stroke" to s.colour)
    }

    if (directLabels) {
      // push labels apart so they don't overlap
      val labelYs = series.map { y(it.values.lastOrNull() ?: 0.0) }.toMutableList()
      val order = labelYs.indices.sortedBy { labelYs[it] }
      for (k in 1 until order.size) {
        val previous = labelYs[order[k - 1]]
        if (labelYs[order[k]] - previous < 14) labelYs[order[k]] = previous + 14
      }
      series.forEachIndexed { i, s ->
        svg.add("circle", "cx" to x(points - 1), "cy" to y(s.values.lastOrNull() ?: 0.0), "r" to 3, "fill" to s.colour)
        svg.add("text", "x" to x(points - 1) + 8, "y" to labelYs[i] + 4, "class" to "chart-direct-label")
          .textContent = s.label.truncate(16)
      }
    }

    // crosshair
    val crosshair = svg.add("line", "y1" to TOP, "y2" to TOP + plotHeight, "class" to "chart-crosshair")
    crosshair.setAttribute("visibility", "hidden")
    val markers = series.map { s ->
      svg.add("circle", "r" to 4, "fill" to s.colour, "class" to "chart-marker").also {
        it.setAttribute("visibility", "hidden")
      }
    }
    val hitArea = svg.add("rect", "x" to LEFT, "y" to TOP, "width" to plotWidth, "height" to plotHeight, "fill" to "transparent")

    hitArea.addEventListener("pointermove", { event ->
      val e = event as MouseEvent
      val bounds = root.getBoundingClientRect()
      val px = e.clientX - bounds.left
      val i = if (points == 1) 0 else ((px - LEFT) / plotWidth * (points - 1)).roundToInt().coerceIn(0, points - 1)
      crosshair.setAttribute("x1", x(i).fmt())
      crosshair.setAttribute("x2", x(i).fmt())
      crosshair.setAttribute("visibility", "visible")
      markers.forEachIndexed { k, marker ->
        marker.setAttribute("cx", x(i).fmt())
        marker.setAttribute("cy", y(series[k].values.getOrElse(i) { 0.0 }).fmt())
        marker.setAttribute("visibility", "visible")
      }
      showTooltip(i, x(i), width)
    })
    hitArea.addEventListener("pointerleave", {
      crosshair.setAttribute("visibility", "hidden")
      markers.forEach { it.setAttribute("visibility", "hidden") }
      tooltip.style.display = "none"
    })

    root.appendChild(svg)
  }


  private fun showTooltip(i: Int, x: Double, width: Double) {
    tooltip.innerHTML = ""
    val title = document.createElement("div")
    title.className = "chart-tooltip-title"
    title.textContent = xLabels[i]
    tooltip.appendChild(title)
    series.sortedByDescending { it.values.getOrElse(i) { 0.0 } }.forEach { s ->
      val row = document.createElement("div")
      row.className = "chart-tooltip-row"
      val swatch = document.createElement("span")
      swatch.className = "chart-swatch"
      (swatch as HTMLElement).style.background = s.colour
      val value = document.createElement("strong")
      value.textContent = formatRate(s.values.getOrElse(i) { 0.0 }) + unit
      val label = document.createElement("span")
      label.textContent = " " + s.label
      row.appendChild(swatch)
      row.appendChild(value)
      row.appendChild(label)
      tooltip.appendChild(row)
    }
    tooltip.style.display = "block"
    // keep the tooltip inside the chart, on the side away from the pointer
    if (x > width / 2) {
      tooltip.style.left = ""
      tooltip.style.right = "${(width - x + 12).fmt()}px"
    } else {
      tooltip.style.right = ""
      tooltip.style.left = "${(x + 12).fmt()}px"
    }
  }


  companion object {
    private const val HEIGHT = 300.0
    private const val LEFT = 56.0
    private const val TOP = 12.0
    private const val BOTTOM = 28.0
  }
}


/** 0, then 3-5 evenly spaced 'nice' values (1, 2, 2.5 or 5 x 10^n) covering [maxValue] */
private fun niceTicks(maxValue: Double): List<Double> {
  if (maxValue <= 0) return listOf(0.0, 1.0)
  val rough = maxValue / 4
  val magnitude = 10.0.pow(floor(log10(rough)))
  val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
  val count = ceil(maxValue / step).toInt()
  return (0..count).map { it * step }
}


/** e.g. `1.2k`, `350`, `2.5` */
fun formatRate(value: Double): String {
  val v = abs(value)
  val sign = if (value < 0) "-" else ""
  return sign + when {
    v >= 1_000_000 -> "${((v / 100_000).roundToInt() / 10.0).trimZero()}M"
    v >= 1_000     -> "${((v / 100).roundToInt() / 10.0).trimZero()}k"
    v >= 100       -> "${v.roundToInt()}"
    v >= 10        -> "${((v * 10).roundToInt() / 10.0).trimZero()}"
    else           -> "${((v * 100).roundToInt() / 100.0).trimZero()}"
  }
}

private fun Double.trimZero(): String = toString().removeSuffix(".0")

private fun Double.fmt(): String = ((this * 10).roundToInt() / 10.0).toString()

private fun String.truncate(length: Int) = if (this.length <= length) this else take(length - 1) + "…"


private const val SVG_NS = "http://www.w3.org/2000/svg"

private fun svg(tag: String, vararg attributes: Pair<String, Any>): Element =
  document.createElementNS(SVG_NS, tag).apply {
    attributes.forEach { (name, value) -> setAttribute(name, value.toString()) }
  }

private fun Element.add(tag: String, vararg attributes: Pair<String, Any>): Element =
  svg(tag, *attributes).also { appendChild(it) }
