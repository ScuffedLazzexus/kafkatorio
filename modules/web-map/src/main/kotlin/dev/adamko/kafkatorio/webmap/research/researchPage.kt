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
import io.kvision.html.image
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


private val researchPageScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


/** How the technologies are shown on the research page */
enum class ResearchLayout { LIST, TREE }


/**
 * The technology tree, and what's researched, with the science packs each technology needs.
 *
 * Shown either as a list of technologies that can be filtered, or as a tree with the
 * prerequisites linked together, like in the game.
 */
fun Container.researchPage(state: SiteState, layout: ResearchLayout) {
  val serverId = state.serverId ?: return
  val page = div(className = "research-page research-page-${layout.name.lowercase()}") {
    researchLayoutTabs(serverId, layout)
  }
  val loading = page.p("Loading research…", className = "home-muted")
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
    val names = Names(itemNames.await(), recipeNames.await())
    page.remove(loading)
    when {
      model.technologies.isEmpty() ->
        page.p("The technology tree hasn't been exported yet - see docs/guide.md.", className = "home-muted")

      layout == ResearchLayout.LIST -> ResearchView(page, serverId, model, names).render()
      layout == ResearchLayout.TREE -> ResearchTreeView(page, model, names).render()
    }
  }
}


fun researchPath(serverId: FactorioServerId, layout: ResearchLayout): String = when (layout) {
  ResearchLayout.LIST -> "/servers/${serverId.id}/research"
  ResearchLayout.TREE -> "/servers/${serverId.id}/research/tree"
}


/** Links to switch between the list and the tree */
private fun Container.researchLayoutTabs(serverId: FactorioServerId, current: ResearchLayout) {
  div(className = "research-layout-tabs") {
    listOf(ResearchLayout.LIST to "List", ResearchLayout.TREE to "Tree").forEach { (layout, label) ->
      link(label, researchPath(serverId, layout), className = "research-filter-button") {
        if (layout == current) addCssClass("active")
        setAttribute("data-navigo", "true")
      }
    }
  }
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
  private lateinit var detail: TechnologyDetail


  fun render() {
    page.header()
    page.remaining()
    page.filters()
    resultCount = page.div(className = "research-count home-muted")
    page.div(className = "research-grid") {
      sortedTechnologies().forEach { name -> cards[name] = technologyCard(name) }
    }
    detail = TechnologyDetail(page, model, names, treeLink = { name ->
      "${researchPath(serverId, ResearchLayout.TREE)}?tech=$name"
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
      onClick { detail.show(name) }
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


  private fun Container.packIcon(pack: String, small: Boolean = false) = packIcon(pack, names, small)


  private fun Container.technologyLink(name: String) {
    span(model.technologies[name]?.displayName(name) ?: name, className = "tech-link") {
      onClick { detail.show(name) }
    }
  }
}
