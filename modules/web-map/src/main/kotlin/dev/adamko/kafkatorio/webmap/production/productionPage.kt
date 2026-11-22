package dev.adamko.kafkatorio.webmap.production

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapSurface
import dev.adamko.kafkatorio.webmap.research.packs
import dev.adamko.kafkatorio.webmap.services.EventsServerClient
import dev.adamko.kafkatorio.webmap.services.ProductionEntry
import dev.adamko.kafkatorio.webmap.services.ProductionReport
import dev.adamko.kafkatorio.webmap.state.SiteState
import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.select.SimpleSelect
import io.kvision.form.text.TextInput
import io.kvision.form.text.TextInputType
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.html.span
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement


private val productionPageScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

/** Reloads the statistics while the page is shown */
private var refreshJob: Int? = null


/** What's produced and consumed, over time. */
fun Container.productionPage(state: SiteState) {
  val serverId = state.serverId ?: return
  val page = div(className = "production-page") {
    p("Loading production statistics…", className = "home-muted")
  }
  productionPageScope.launch {
    val itemNames = async { runCatching { EventsServerClient.itemNames() }.getOrDefault(emptyMap()) }
    val fluidNames = async { runCatching { EventsServerClient.fluidNames() }.getOrDefault(emptyMap()) }
    val surfaces = async { runCatching { EventsServerClient.surfaces(serverId) }.getOrDefault(emptyList()) }
    val sciencePacks = async {
      runCatching { EventsServerClient.technologies() }.getOrDefault(emptyMap())
        .values.flatMap { it.packs() }.toSet()
    }
    page.removeAll()
    ProductionView(
      page = page,
      serverId = serverId,
      itemNames = itemNames.await(),
      fluidNames = fluidNames.await(),
      surfaces = surfaces.await(),
      sciencePacks = sciencePacks.await(),
    ).render()
  }
}


private enum class Kind(val label: String, val iconDir: String) {
  ITEMS("Items", "item"),
  FLUIDS("Fluids", "fluid"),
}


private enum class Range(val label: String, val api: String) {
  TEN_MINUTES("10m", "TEN_MINUTES"),
  ONE_HOUR("1h", "ONE_HOUR"),
  SIX_HOURS("6h", "SIX_HOURS"),
  ONE_DAY("24h", "ONE_DAY"),
  ONE_WEEK("7d", "ONE_WEEK"),
}


private enum class SortColumn { NAME, PRODUCED, CONSUMED, NET }


private class ProductionView(
  private val page: Container,
  private val serverId: FactorioServerId,
  private val itemNames: Map<String, String>,
  private val fluidNames: Map<String, String>,
  private val surfaces: List<MapSurface>,
  private val sciencePacks: Set<String>,
) {

  // filters
  private var surfaceIndex: Int? = 1
  private var range = Range.ONE_HOUR
  private var kind = Kind.ITEMS
  private var search = ""
  private var sortColumn = SortColumn.PRODUCED
  private var sortDescending = true

  private var report: ProductionReport? = null
  private var loading: Job? = null

  /** Charted entries (`kind/name`), with their colour slot - colour follows the entry */
  private val charted = linkedMapOf<String, Int>()
  private var chartedChosenByUser = false

  private lateinit var rangeButtons: Map<Range, Button>
  private lateinit var kindButtons: Map<Kind, Button>
  private lateinit var tiles: Div
  private lateinit var legend: Div
  private lateinit var chart: LineChart
  private lateinit var table: Div
  private lateinit var status: Div
  private lateinit var tableTitle: io.kvision.html.H2


  fun render() {
    page.div(className = "production-header") {
      h1("Production")
      status = div(className = "home-muted")
    }
    page.filters()
    tiles = page.div(className = "production-tiles")

    page.div(className = "production-section") {
      h2("Production per minute")
      legend = div(className = "chart-legend")
      val chartRoot = div(className = "line-chart-root")
      chartRoot.addAfterInsertHook {
        if (!::chart.isInitialized) {
          chart = LineChart(chartRoot.getElement() as HTMLElement, unit = "/min")
          // don't change other widgets while KVision is still inserting this one
          window.setTimeout({ redrawAll() }, 0)
        }
      }
    }

    page.div(className = "production-section") {
      div(className = "production-table-header") {
        tableTitle = h2("")
        val searchInput = TextInput(TextInputType.SEARCH) {
          placeholder = "Search"
          addCssClass("research-search")
        }
        searchInput.onEvent {
          input = {
            search = searchInput.value?.trim()?.lowercase() ?: ""
            renderTable()
          }
        }
        add(searchInput)
      }
      p("Select up to ${PALETTE.size} rows to chart them.", className = "home-muted")
      table = div(className = "production-table")
    }

    window.addEventListener("resize", { if (::chart.isInitialized) chart.redraw() })

    refreshJob?.let { window.clearInterval(it) }
    refreshJob = window.setInterval({
      // stop when the page isn't shown any more
      if (!page.getElement().isConnectedCompat()) {
        refreshJob?.let { window.clearInterval(it) }
      } else {
        load()
      }
    }, 60_000)

    load()
  }


  private fun Container.filters() {
    div(className = "research-filters") {
      val options = listOf("" to "All surfaces") +
          surfaces.map { "${it.index}" to it.name.replaceFirstChar { c -> c.uppercase() } }
      add(SimpleSelect(options = options, value = "1") {
        addCssClass("production-surface")
        onEvent {
          change = {
            surfaceIndex = self.value?.toIntOrNull()
            load()
          }
        }
      })

      div(className = "research-status-filter") {
        rangeButtons = Range.values().associateWith { r ->
          button(r.label, className = "research-filter-button") {
            title = "The last ${r.label} of game time"
            onClick {
              range = r
              load()
            }
          }
        }
      }

      div(className = "research-status-filter") {
        kindButtons = Kind.values().associateWith { k ->
          button(k.label, className = "research-filter-button") {
            onClick {
              if (kind != k) {
                kind = k
                charted.clear()
                chartedChosenByUser = false
                redrawAll()
              }
            }
          }
        }
      }
    }
  }


  private fun load() {
    loading?.cancel()
    updateButtons()
    loading = productionPageScope.launch {
      val loaded = runCatching { EventsServerClient.production(serverId, surfaceIndex, range.api) }
        .onFailure { println("[productionPage] failed to load production: $it") }
        .getOrNull()
      report = loaded
      redrawAll()
    }
  }


  private fun updateButtons() {
    rangeButtons.forEach { (r, b) -> if (r == range) b.addCssClass("active") else b.removeCssClass("active") }
    kindButtons.forEach { (k, b) -> if (k == kind) b.addCssClass("active") else b.removeCssClass("active") }
  }


  private fun redrawAll() {
    updateButtons()
    val report = report
    status.content = when {
      report == null            -> "Couldn't load the production statistics."
      report.latestTick == null -> "The game hasn't sent production statistics yet. They're sent every minute."
      else                      -> "Game time ${formatGameTime(report.latestTick)} · averages over the last " +
          formatDuration(coveredMinutes()) +
          if (coveredMinutes() < rangeMinutes(range)) " (statistics started then)" else ""
    }
    renderTiles()
    renderChart()
    renderTable()
  }


  /** Averages are per minute of the range that has statistics, so a new server isn't diluted */
  private val minutes: Double get() = coveredMinutes().toDouble()


  /** The minutes of the range since the first statistics, rounded to whole buckets */
  private fun coveredMinutes(): Int {
    val report = report ?: return rangeMinutes(range)
    val buckets = report.entries.firstOrNull()?.producedSeries?.size ?: return rangeMinutes(range)
    val firstBucket = report.entries.minOfOrNull { entry ->
      (entry.producedSeries.indices).firstOrNull { entry.producedSeries[it] > 0 || entry.consumedSeries[it] > 0 }
        ?: buckets
    } ?: 0
    return maxOf(1, buckets - firstBucket) * report.bucketMinutes
  }

  private fun rangeMinutes(r: Range) = when (r) {
    Range.TEN_MINUTES -> 10
    Range.ONE_HOUR    -> 60
    Range.SIX_HOURS   -> 6 * 60
    Range.ONE_DAY     -> 24 * 60
    Range.ONE_WEEK    -> 7 * 24 * 60
  }

  private fun entries(): List<ProductionEntry> =
    report?.entries.orEmpty().filter { it.kind == kind.iconDir }


  /** Science per minute: the science packs consumed by research */
  private fun renderTiles() {
    tiles.removeAll()
    val packs = report?.entries.orEmpty().filter { it.kind == "item" && it.name in sciencePacks && it.consumed > 0 }
      .sortedByDescending { it.consumed }
    val spm = packs.sumOf { it.consumed } / minutes

    tiles.div(className = "stat-tile") {
      div("Science packs per minute", className = "stat-tile-label")
      div(formatRate(spm), className = "stat-tile-value")
      div("consumed by research", className = "stat-tile-sub")
    }
    if (packs.isNotEmpty()) {
      tiles.div(className = "stat-tile stat-tile-wide") {
        div("By science pack", className = "stat-tile-label")
        div(className = "pack-rates") {
          packs.forEach { entry ->
            div(className = "pack-rate") {
              title = name(entry)
              image("/kafkatorio/icons/item/${entry.name}.png", alt = name(entry), className = "pack-icon pack-icon-small")
              span("${formatRate(entry.consumed / minutes)}/min")
            }
          }
        }
      }
    }
  }


  private fun renderChart() {
    if (!::chart.isInitialized) return
    val entries = entries()

    // by default, chart what's produced the most
    if (!chartedChosenByUser) {
      charted.clear()
      entries.take(DEFAULT_CHARTED).forEach { addToChart(it) }
    }

    val bucketMinutes = report?.bucketMinutes ?: 1
    val byKey = entries.associateBy { key(it) }
    val series = charted.mapNotNull { (key, slot) ->
      val entry = byKey[key] ?: return@mapNotNull null
      ChartSeries(name(entry), PALETTE[slot], entry.producedSeries.map { it / bucketMinutes })
    }
    val buckets = report?.entries?.firstOrNull()?.producedSeries?.size ?: 0
    val labels = (0 until buckets).map { i ->
      val minutesAgo = (buckets - 1 - i) * bucketMinutes
      if (minutesAgo == 0) "now" else "-${formatDuration(minutesAgo)}"
    }
    chart.render(series, labels)

    legend.removeAll()
    series.forEach { s ->
      legend.div(className = "chart-legend-item") {
        span(className = "chart-swatch") { setStyle("background", s.colour) }
        span(s.label)
      }
    }
  }


  private fun addToChart(entry: ProductionEntry): Boolean {
    if (charted.size >= PALETTE.size) return false
    val used = charted.values.toSet()
    charted[key(entry)] = PALETTE.indices.first { it !in used }
    return true
  }


  private fun renderTable() {
    tableTitle.content = "All ${kind.label.lowercase()}"
    table.removeAll()
    val rows = entries()
      .filter { search.isEmpty() || name(it).lowercase().contains(search) || it.name.contains(search) }
      .sortedWith(comparator())

    if (rows.isEmpty()) {
      table.p("Nothing to show.", className = "home-muted")
      return
    }

    table.div(className = "production-row production-row-header") {
      headerCell("", null)
      headerCell(kind.label.removeSuffix("s"), SortColumn.NAME)
      headerCell("Produced /min", SortColumn.PRODUCED)
      headerCell("Consumed /min", SortColumn.CONSUMED)
      headerCell("Net /min", SortColumn.NET)
    }
    rows.forEach { entry ->
      val slot = charted[key(entry)]
      table.div(className = "production-row" + if (slot != null) " charted" else "") {
        span(className = "chart-swatch production-row-swatch") {
          if (slot != null) setStyle("background", PALETTE[slot])
        }
        div(className = "production-name") {
          image("/kafkatorio/icons/${kind.iconDir}/${entry.name}.png", alt = "", className = "pack-icon pack-icon-small") {
            setAttribute("loading", "lazy")
          }
          span(name(entry))
        }
        div(formatRate(entry.produced / minutes), className = "production-number")
        div(formatRate(entry.consumed / minutes), className = "production-number")
        val net = (entry.produced - entry.consumed) / minutes
        div((if (net > 0) "+" else "") + formatRate(net), className = "production-number")
        onClick {
          chartedChosenByUser = true
          if (charted.remove(key(entry)) == null) addToChart(entry)
          renderChart()
          renderTable()
        }
      }
    }
  }


  private fun Container.headerCell(label: String, column: SortColumn?) {
    div(className = "production-header-cell" + if (column == sortColumn) " sorted" else "") {
      content = label + if (column == sortColumn) (if (sortDescending) " ▼" else " ▲") else ""
      if (column != null) {
        onClick {
          if (sortColumn == column) sortDescending = !sortDescending
          else {
            sortColumn = column
            sortDescending = column != SortColumn.NAME
          }
          renderTable()
        }
      }
    }
  }


  private fun comparator(): Comparator<ProductionEntry> {
    val base: Comparator<ProductionEntry> = when (sortColumn) {
      SortColumn.NAME     -> compareBy { name(it).lowercase() }
      SortColumn.PRODUCED -> compareBy { it.produced }
      SortColumn.CONSUMED -> compareBy { it.consumed }
      SortColumn.NET      -> compareBy { it.produced - it.consumed }
    }
    return if (sortDescending) base.reversed() else base
  }


  private fun key(entry: ProductionEntry) = "${entry.kind}/${entry.name}"

  private fun name(entry: ProductionEntry): String {
    val names = if (entry.kind == "fluid") fluidNames else itemNames
    return names[entry.name] ?: entry.name.replace('-', ' ').replaceFirstChar { it.uppercase() }
  }


  companion object {
    private const val DEFAULT_CHARTED = 5

    /** Categorical colours, validated for CVD separation on the dark chart surface */
    val PALETTE = listOf(
      "#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767",
    )
  }
}


private fun org.w3c.dom.Node?.isConnectedCompat(): Boolean = this?.asDynamic()?.isConnected == true


private fun formatDuration(minutes: Int): String = when {
  minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)}d"
  minutes % 60 == 0        -> "${minutes / 60}h"
  minutes > 60             -> "${minutes / 60}h${minutes % 60}m"
  else                     -> "${minutes}m"
}


/** e.g. `412h 05m` */
private fun formatGameTime(tick: Long): String {
  val totalMinutes = tick / 3600
  return "${totalMinutes / 60}h ${(totalMinutes % 60).toString().padStart(2, '0')}m"
}
