package dev.adamko.kafkatorio.webmap.research

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.webmap.services.EventsServerClient
import dev.adamko.kafkatorio.webmap.services.TechnologyInfo
import dev.adamko.kafkatorio.webmap.state.SiteState
import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.text.TextInput
import io.kvision.form.text.TextInputType
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.image
import io.kvision.html.p
import io.kvision.html.span
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.events.KeyboardEvent


private val researchPageScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


/** The technology tree, and what's researched, with the science packs each technology needs. */
fun Container.researchPage(state: SiteState) {
  val serverId = state.serverId ?: return
  val page = div(className = "research-page") {
    p("Loading research…", className = "home-muted")
  }
  researchPageScope.launch {
    val technologies = async {
      runCatching { EventsServerClient.technologies() }
        .onFailure { println("[researchPage] failed to load technologies: $it") }
        .getOrDefault(emptyMap())
    }
    val itemNames = async { runCatching { EventsServerClient.itemNames() }.getOrDefault(emptyMap()) }
    val recipeNames = async { runCatching { EventsServerClient.recipeNames() }.getOrDefault(emptyMap()) }
    val research = async { runCatching { EventsServerClient.research(serverId) }.getOrNull() }

    val model = ResearchModel(technologies.await(), research.await())
    page.removeAll()
    if (model.technologies.isEmpty()) {
      page.p("The technology tree hasn't been exported yet - see docs/guide.md.", className = "home-muted")
    } else {
      ResearchView(page, serverId, model, Names(itemNames.await(), recipeNames.await())).render()
    }
  }
}


private class Names(
  val items: Map<String, String>,
  val recipes: Map<String, String>,
) {
  fun item(name: String) = items[name] ?: name
  fun recipe(name: String) = recipes[name] ?: name
}


private class ResearchView(
  private val page: Container,
  private val serverId: FactorioServerId,
  private val model: ResearchModel,
  private val names: Names,
) {

  private val cards = mutableMapOf<String, Div>()

  // filters
  private var search = ""
  private var statusFilter: Set<TechnologyStatus>? = null
  private var packFilter: String? = null

  private lateinit var packFilterButtons: Map<String, Div>
  private lateinit var statusButtons: Map<Set<TechnologyStatus>?, io.kvision.html.Button>
  private lateinit var resultCount: Div
  private lateinit var detailOverlay: Div


  fun render() {
    page.header()
    page.remaining()
    page.filters()
    resultCount = page.div(className = "research-count home-muted")
    page.div(className = "research-grid") {
      sortedTechnologies().forEach { name -> cards[name] = technologyCard(name) }
    }
    detailOverlay = page.div(className = "research-detail-overlay") {
      visible = false
      onClick { event -> if (event.target == getElement()) closeDetail() }
    }
    document.addEventListener("keydown", { event ->
      if ((event as KeyboardEvent).key == "Escape") closeDetail()
    })
    applyFilters()
  }


  private fun sortedTechnologies(): List<String> =
    model.technologies.keys.sortedWith(
      compareBy<String>(
        { model.status[it]?.ordinal ?: 0 },
        { if (model.status[it] == TechnologyStatus.QUEUED) model.research?.queue?.indexOf(it) ?: 0 else 0 },
        { model.depth[it] ?: 0 },
        { model.technologies[it]?.order ?: "" },
        { model.technologies[it]?.name ?: it },
      )
    )


  private fun Container.header() {
    div(className = "research-header") {
      h1("Research")
      val total = model.finiteCount
      val done = model.researchedCount
      div(className = "research-summary") {
        span("$done of $total technologies researched", className = "research-summary-count")
        div(className = "research-progress") {
          div(className = "research-progress-bar") {
            setStyle("width", "${if (total == 0) 0 else 100 * done / total}%")
          }
        }
      }

      val research = model.research
      when {
        research == null         -> p(
          "The game hasn't sent its research yet. It's sent once a minute, and whenever research changes.",
          className = "home-muted",
        )

        research.current == null -> p("Nothing is being researched.", className = "home-muted")

        else                     -> div(className = "research-current") {
          val current = research.current!!
          span("Researching ", className = "home-muted")
          technologyLink(current)
          val progress = ((research.progress ?: 0.0) * 100).toInt()
          span(" — $progress%")
          div(className = "research-progress research-progress-small") {
            div(className = "research-progress-bar") { setStyle("width", "$progress%") }
          }
          val queued = research.queue.filter { it != current }
          if (queued.isNotEmpty()) {
            div(className = "research-queue") {
              span("Queue: ", className = "home-muted")
              queued.forEach { technologyLink(it) }
            }
          }
        }
      }
    }
  }


  private fun Container.remaining() {
    val remaining = model.remainingCost()
    if (remaining.isEmpty()) return
    div(className = "research-section") {
      h2("Science packs needed for everything left")
      p("Excluding infinite research. Click a science pack to show the technologies that need it.", className = "home-muted")
      packFilterButtons = model.sciencePacks.filter { it in remaining }.associateWith { pack ->
        div(className = "pack-total") {
          title = names.item(pack)
          packIcon(pack)
          span(formatAmount(remaining.getValue(pack)))
          onClick { togglePackFilter(pack) }
        }
      }
    }
  }


  private fun Container.filters() {
    div(className = "research-filters") {
      val searchInput = TextInput(TextInputType.SEARCH) {
        placeholder = "Search technologies or recipes"
        addCssClass("research-search")
      }
      searchInput.onEvent {
        input = {
          search = searchInput.value?.trim()?.lowercase() ?: ""
          applyFilters()
        }
      }
      add(searchInput)

      val options = listOf(
        "All" to null,
        "Available" to setOf(TechnologyStatus.RESEARCHING, TechnologyStatus.QUEUED, TechnologyStatus.AVAILABLE),
        "Locked" to setOf(TechnologyStatus.LOCKED),
        "Researched" to setOf(TechnologyStatus.RESEARCHED),
      )
      div(className = "research-status-filter") {
        statusButtons = options.associate { (label, statuses) ->
          statuses to button(label, className = "research-filter-button") {
            onClick {
              statusFilter = statuses
              applyFilters()
            }
          }
        }
      }
    }
  }


  private fun togglePackFilter(pack: String) {
    packFilter = if (packFilter == pack) null else pack
    applyFilters()
  }


  private fun applyFilters() {
    var shown = 0
    cards.forEach { (name, card) ->
      val tech = model.technologies.getValue(name)
      val visible = (statusFilter == null || model.status[name] in statusFilter!!)
          && (packFilter == null || packFilter in tech.packs())
          && (search.isEmpty() || matchesSearch(name, tech))
      card.visible = visible
      if (visible) shown++
    }
    statusButtons.forEach { (statuses, button) ->
      if (statuses == statusFilter) button.addCssClass("active") else button.removeCssClass("active")
    }
    if (::packFilterButtons.isInitialized) {
      packFilterButtons.forEach { (pack, button) ->
        if (pack == packFilter) button.addCssClass("active") else button.removeCssClass("active")
      }
    }
    val packText = packFilter?.let { " that need ${names.item(it)}" } ?: ""
    resultCount.content = "$shown technologies$packText"
  }


  private fun matchesSearch(name: String, tech: TechnologyInfo): Boolean =
    tech.name.lowercase().contains(search)
        || name.contains(search)
        || tech.unlocks.any { names.recipe(it).lowercase().contains(search) }


  private fun Container.technologyCard(name: String): Div {
    val tech = model.technologies.getValue(name)
    val status = model.status[name] ?: TechnologyStatus.LOCKED
    return div(className = "tech-card tech-${status.name.lowercase()}") {
      image("/kafkatorio/icons/technology/$name.png", alt = "", className = "tech-card-icon") {
        setAttribute("loading", "lazy")
      }
      div(className = "tech-card-body") {
        div(className = "tech-card-name") { content = tech.displayName(name) }
        div(className = "tech-card-cost") { costSummary(name, tech) }
        span(status.label, className = "tech-status")
      }
      onClick { showDetail(name) }
    }
  }


  /** e.g. `150 × [packs]`, or `Craft 10 Iron plate` */
  private fun Container.costSummary(name: String, tech: TechnologyInfo) {
    val units = model.units(name)
    if (units == null) {
      span(tech.triggerDescription(names), className = "home-muted")
      return
    }
    span("${formatAmount(units)} ×")
    tech.packs().forEach { pack -> packIcon(pack, small = true) }
  }


  private fun Container.packIcon(pack: String, small: Boolean = false) {
    image("/kafkatorio/icons/item/$pack.png", alt = names.item(pack), className = if (small) "pack-icon pack-icon-small" else "pack-icon") {
      title = names.item(pack)
    }
  }


  private fun Container.technologyLink(name: String) {
    span(model.technologies[name]?.displayName(name) ?: name, className = "tech-link") {
      onClick { showDetail(name) }
    }
  }


  private fun closeDetail() {
    detailOverlay.visible = false
    detailOverlay.removeAll()
  }


  private fun showDetail(name: String) {
    val tech = model.technologies[name] ?: return
    val status = model.status[name] ?: TechnologyStatus.LOCKED
    detailOverlay.removeAll()
    detailOverlay.div(className = "research-detail tech-${status.name.lowercase()}") {
      button("×", className = "research-detail-close") {
        title = "Close"
        onClick { closeDetail() }
      }
      div(className = "research-detail-title") {
        image("/kafkatorio/icons/technology/$name.png", alt = "", className = "research-detail-icon")
        div {
          h3(tech.displayName(name))
          span(status.label, className = "tech-status")
          model.level(name)?.let { level ->
            val max = tech.maxLevel?.jsonPrimitive?.let { it.intOrNull?.toString() ?: it.contentOrNull }
            span(" Level $level${if (max == "infinite") " (infinite)" else ""}", className = "home-muted")
          }
        }
      }

      val units = model.units(name)
      div(className = "research-detail-section") {
        h3("Cost")
        if (units == null) {
          p(tech.triggerDescription(names))
        } else {
          val time = model.timePerUnit(name)
          p("${formatAmount(units)} units${time?.let { ", ${formatAmount(it)}s each" } ?: ""}", className = "home-muted")
          packList(model.cost(name))
        }
      }

      if (status != TechnologyStatus.RESEARCHED) {
        val (toReach, count) = model.costToReach(name)
        if (count > 1) {
          div(className = "research-detail-section") {
            h3("Total to reach")
            p("$count technologies, including the prerequisites that aren't researched yet", className = "home-muted")
            packList(toReach)
          }
        }
      }

      technologyList("Requires", tech.prerequisites)
      technologyList("Leads to", model.successors[name].orEmpty())

      if (tech.unlocks.isNotEmpty()) {
        div(className = "research-detail-section") {
          h3("Unlocks")
          div(className = "recipe-list") {
            tech.unlocks.forEach { recipe ->
              div(className = "recipe-item") {
                image("/kafkatorio/icons/recipe/$recipe.png", alt = "", className = "pack-icon pack-icon-small")
                span(names.recipe(recipe))
              }
            }
          }
        }
      }
    }
    detailOverlay.visible = true
  }


  private fun Container.packList(cost: SciencePackCost) {
    div(className = "pack-list") {
      model.sciencePacks.filter { it in cost }.forEach { pack ->
        div(className = "pack-total") {
          title = names.item(pack)
          packIcon(pack)
          span(formatAmount(cost.getValue(pack)))
        }
      }
    }
  }


  private fun Container.technologyList(title: String, technologies: List<String>) {
    val known = technologies.filter { it in model.technologies }
    if (known.isEmpty()) return
    div(className = "research-detail-section") {
      h3(title)
      div(className = "tech-chip-list") {
        known.sortedBy { model.depth[it] ?: 0 }.forEach { name ->
          val status = model.status[name] ?: TechnologyStatus.LOCKED
          div(className = "tech-chip tech-${status.name.lowercase()}") {
            image("/kafkatorio/icons/technology/$name.png", alt = "", className = "pack-icon pack-icon-small")
            span(model.technologies.getValue(name).displayName(name))
            onClick { showDetail(name) }
          }
        }
      }
    }
  }
}


/** The in-game name. Technologies with levels (e.g. `Mining productivity 3`) already include it. */
private fun TechnologyInfo.displayName(id: String): String = name.ifBlank { id }


/** e.g. `Craft 10 Iron plate`, for technologies researched by doing something */
private fun TechnologyInfo.triggerDescription(names: Names): String {
  val trigger = trigger ?: return "Unknown cost"
  fun field(key: String) = trigger[key]?.jsonPrimitive?.contentOrNull
  val type = field("type") ?: return "Unknown cost"
  val count = field("count")?.let { "$it " } ?: ""
  return when (type) {
    "craft-item"   -> "Craft $count${names.item(field("item") ?: "?")}"
    "mine-entity"  -> "Mine ${names.item(field("entity") ?: "?").replace('-', ' ')}"
    "build-entity" -> "Build ${names.item(field("entity") ?: "?").replace('-', ' ')}"
    else           -> type.replace('-', ' ').replaceFirstChar { it.uppercase() }
  }
}


private fun formatAmount(amount: Double): String = when {
  amount >= 1_000_000_000 -> "${(amount / 100_000_000).toLong() / 10.0}B"
  amount >= 1_000_000     -> "${(amount / 100_000).toLong() / 10.0}M"
  amount >= 10_000        -> "${(amount / 100).toLong() / 10.0}k"
  amount == kotlin.math.floor(amount) -> amount.toLong().toString()
  else                    -> "${(amount * 10).toLong() / 10.0}"
}
