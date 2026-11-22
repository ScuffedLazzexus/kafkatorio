package dev.adamko.kafkatorio.webmap.research

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.core.onEvent
import io.kvision.form.text.TextInput
import io.kvision.form.text.TextInputType
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.html.span
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.url.URLSearchParams


private const val SVG_NS = "http://www.w3.org/2000/svg"

private const val NODE_WIDTH = 104.0
private const val NODE_HEIGHT = 88.0

private const val MIN_SCALE = 0.04
private const val MAX_SCALE = 2.0

/** Below this zoom, names are hidden, so the tree is readable as an overview */
private const val LABEL_SCALE = 0.45


/**
 * The technology tree, drawn like the game's technology screen: technologies are linked to their
 * prerequisites, from the first technologies at the top to the last at the bottom.
 *
 * Drag to move, scroll (or pinch) to zoom. Selecting a technology highlights everything needed to
 * research it, and what it leads to.
 */
internal class ResearchTreeView(
  private val page: Container,
  private val model: ResearchModel,
  private val names: Names,
) {

  private val layout = ResearchTreeLayout(model)

  /** All prerequisites of each technology, including indirect ones */
  private val ancestors = mutableMapOf<String, Set<String>>()

  private lateinit var viewport: Div
  private lateinit var canvas: HTMLElement
  private var canvasRequested = false
  private lateinit var selectionBar: Div
  private lateinit var searchCount: Div
  private lateinit var detail: TechnologyDetail

  private val nodes = mutableMapOf<String, HTMLElement>()
  private val edges = mutableListOf<Triple<String, String, Element>>()

  // view
  private var scale = 1.0
  private var offsetX = 0.0
  private var offsetY = 0.0

  private var selected: String? = null
  private var searchMatches: List<String> = emptyList()
  private var searchIndex = -1


  fun render() {
    page.toolbar()
    selectionBar = page.div(className = "research-tree-selection")
    viewport = page.div(className = "research-tree-viewport")
    viewport.addAfterInsertHook {
      if (!canvasRequested) {
        canvasRequested = true
        val element = viewport.getElement() as HTMLElement
        // don't change other widgets while KVision is still inserting this one
        window.setTimeout({ initCanvas(element) }, 0)
      }
    }
    page.div(className = "research-tree-help home-muted") {
      content = "Drag to move, scroll or pinch to zoom. Click a technology to show what it needs; double-click for details."
    }

    // registered before the pop-up's own listener, so Escape closes the pop-up before the selection
    document.addEventListener("keydown", { event ->
      if ((event as KeyboardEvent).key != "Escape" || !::canvas.isInitialized || !canvas.isConnected) return@addEventListener
      if (!detail.isOpen) select(null)
    })
    detail = TechnologyDetail(page, model, names, onShow = { name ->
      // follow technologies opened from the pop-up, so the tree shows them when it's closed
      if (::canvas.isInitialized && name != selected) select(name, centre = true)
    })
    renderSelectionBar()
  }


  private fun Container.toolbar() {
    div(className = "research-tree-toolbar") {
      val searchInput = TextInput(TextInputType.SEARCH) {
        placeholder = "Search technologies or recipes"
        addCssClass("research-search")
      }
      searchInput.onEvent {
        input = { search(searchInput.value?.trim()?.lowercase() ?: "") }
        keydown = { event ->
          if (event.key == "Enter") {
            event.preventDefault()
            nextSearchMatch(if (event.shiftKey) -1 else 1)
          }
        }
      }
      add(searchInput)
      searchCount = div(className = "research-count home-muted")

      div(className = "research-tree-zoom") {
        button("−", className = "research-filter-button") {
          title = "Zoom out"
          onClick { zoomBy(1 / 1.4) }
        }
        button("+", className = "research-filter-button") {
          title = "Zoom in"
          onClick { zoomBy(1.4) }
        }
        button("Fit", className = "research-filter-button") {
          title = "Show the whole tree"
          onClick { fit() }
        }
        model.research?.current?.let { current ->
          button("Current research", className = "research-filter-button") {
            onClick { select(current, centre = true) }
          }
        }
      }

      div(className = "research-tree-legend") {
        listOf(
          TechnologyStatus.RESEARCHED,
          TechnologyStatus.RESEARCHING,
          TechnologyStatus.AVAILABLE,
          TechnologyStatus.LOCKED,
        ).forEach { status ->
          span(status.label, className = "research-tree-legend-item tech-${status.name.lowercase()}")
        }
      }
    }
  }


  //region drawing

  private fun initCanvas(viewportElement: HTMLElement) {
    canvas = document.createElement("div") as HTMLElement
    canvas.className = "research-tree-canvas"
    canvas.style.width = "${layout.width}px"
    canvas.style.height = "${layout.height}px"

    val svg = document.createElementNS(SVG_NS, "svg")
    svg.setAttribute("class", "research-tree-edges")
    svg.setAttribute("width", "${layout.width}")
    svg.setAttribute("height", "${layout.height}")
    canvas.appendChild(svg)

    model.technologies.forEach { (name, tech) ->
      val to = layout.positions[name] ?: return@forEach
      tech.prerequisites.forEach { prerequisite ->
        val from = layout.positions[prerequisite] ?: return@forEach
        val path = document.createElementNS(SVG_NS, "path")
        // from the bottom of the prerequisite to the top of the technology that needs it
        val y1 = from.y + NODE_HEIGHT / 2
        val y2 = to.y - NODE_HEIGHT / 2
        val bend = max(40.0, (y2 - y1) / 2)
        path.setAttribute("d", "M${from.x},$y1 C${from.x},${y1 + bend} ${to.x},${y2 - bend} ${to.x},$y2")
        val done = model.status[prerequisite] == TechnologyStatus.RESEARCHED
        path.setAttribute("class", if (done) "tree-edge tree-edge-done" else "tree-edge")
        svg.appendChild(path)
        edges += Triple(prerequisite, name, path)
      }
    }

    model.technologies.forEach { (name, tech) ->
      val position = layout.positions[name] ?: return@forEach
      val status = model.status[name] ?: TechnologyStatus.LOCKED
      val node = document.createElement("div") as HTMLElement
      node.className = "tree-node tech-${status.name.lowercase()}"
      node.setAttribute("data-tech", name)
      node.style.left = "${position.x - NODE_WIDTH / 2}px"
      node.style.top = "${position.y - NODE_HEIGHT / 2}px"
      node.title = "${tech.displayName(name)}\n${status.label}\n${costText(name)}"

      val icon = document.createElement("img") as HTMLElement
      icon.className = "tree-node-icon"
      icon.setAttribute("src", "/kafkatorio/icons/technology/$name.png")
      icon.setAttribute("alt", "")
      icon.setAttribute("loading", "lazy")
      icon.setAttribute("draggable", "false")
      node.appendChild(icon)

      val label = document.createElement("div") as HTMLElement
      label.className = "tree-node-name"
      label.textContent = tech.displayName(name)
      node.appendChild(label)

      canvas.appendChild(node)
      nodes[name] = node
    }

    viewportElement.appendChild(canvas)
    initPanZoom(viewportElement)

    val requested = URLSearchParams(window.location.search).get("tech")?.takeIf { it in model.technologies }
    val initial = requested ?: model.research?.current?.takeIf { it in model.technologies }
    if (initial != null) {
      scale = 1.0
      select(initial, centre = true)
    } else {
      showTop()
    }
  }


  private fun costText(name: String): String {
    val units = model.units(name) ?: return model.technologies.getValue(name).triggerDescription(names)
    val packs = model.cost(name).keys.joinToString(", ") { names.item(it) }
    return "${formatAmount(units)} × $packs"
  }

  //endregion


  //region selection

  private fun ancestorsOf(name: String): Set<String> = ancestors.getOrPut(name) {
    val result = mutableSetOf<String>()
    val stack = ArrayDeque(model.technologies[name]?.prerequisites.orEmpty())
    while (stack.isNotEmpty()) {
      val next = stack.removeLast()
      if (next in model.technologies && result.add(next)) {
        stack.addAll(model.technologies.getValue(next).prerequisites)
      }
    }
    result
  }


  private fun select(name: String?, centre: Boolean = false) {
    val name = name?.takeIf { it in model.technologies }
    selected = name

    val chain = name?.let { ancestorsOf(it) } ?: emptySet()
    val next = name?.let { model.successors[it].orEmpty().toSet() } ?: emptySet()

    canvas.classList.toggle("has-selection", name != null)
    nodes.forEach { (tech, node) ->
      node.classList.toggle("tree-selected", tech == name)
      node.classList.toggle("tree-ancestor", tech in chain)
      node.classList.toggle("tree-successor", tech in next)
    }
    edges.forEach { (from, to, path) ->
      val onChain = to == name || to in chain
      val toNext = from == name && to in next
      path.classList.toggle("tree-edge-chain", onChain)
      path.classList.toggle("tree-edge-next", toNext)
      // draw highlighted links on top
      if (onChain || toNext) path.parentNode?.appendChild(path)
    }

    if (name != null && centre) centreOn(name)
    renderSelectionBar()

    val url = window.location.pathname + (name?.let { "?tech=$it" } ?: "")
    window.history.replaceState(null, "", url)
  }


  private fun renderSelectionBar() {
    selectionBar.removeAll()
    val name = selected
    if (name == null) {
      selectionBar.span(
        "Select a technology to highlight everything it needs.",
        className = "home-muted",
      )
      return
    }
    val tech = model.technologies.getValue(name)
    val status = model.status[name] ?: TechnologyStatus.LOCKED
    selectionBar.div(className = "research-tree-selected tech-${status.name.lowercase()}") {
      image("/kafkatorio/icons/technology/$name.png", alt = "", className = "pack-icon")
      span(tech.displayName(name), className = "tech-card-name")
      span(status.label, className = "tech-status")
    }
    if (status != TechnologyStatus.RESEARCHED) {
      val (toReach, count) = model.costToReach(name)
      selectionBar.div(className = "research-tree-to-reach") {
        span(
          if (count <= 1) "No prerequisites left" else "${count - 1} prerequisites left",
          className = "home-muted",
        )
        model.sciencePacks.filter { it in toReach }.forEach { pack ->
          span(className = "research-tree-pack") {
            packIcon(pack, names, small = true)
            span(formatAmount(toReach.getValue(pack)))
          }
        }
      }
    }
    selectionBar.div(className = "research-tree-selection-actions") {
      button("Details", className = "research-filter-button") {
        onClick { detail.show(name) }
      }
      button("Clear", className = "research-filter-button") {
        onClick { select(null) }
      }
    }
  }

  //endregion


  //region search

  private fun search(text: String) {
    searchMatches = if (text.isEmpty()) emptyList() else {
      model.technologies.filter { (name, tech) ->
        tech.displayName(name).lowercase().contains(text)
            || name.contains(text)
            || tech.unlocks.any { names.recipe(it).lowercase().contains(text) }
      }.keys.sortedWith(compareBy({ layout.positions[it]?.y ?: 0.0 }, { layout.positions[it]?.x ?: 0.0 }))
    }
    searchIndex = -1
    val matches = searchMatches.toSet()
    canvas.classList.toggle("has-search", text.isNotEmpty())
    nodes.forEach { (name, node) -> node.classList.toggle("tree-match", name in matches) }
    searchCount.content = when {
      text.isEmpty()          -> ""
      searchMatches.isEmpty() -> "No matches"
      else                    -> "${searchMatches.size} matches — press Enter to go to each"
    }
  }


  private fun nextSearchMatch(step: Int) {
    if (searchMatches.isEmpty()) return
    searchIndex = (searchIndex + step).mod(searchMatches.size)
    select(searchMatches[searchIndex], centre = true)
    searchCount.content = "${searchIndex + 1} of ${searchMatches.size}"
  }

  //endregion


  //region pan and zoom

  private fun applyTransform() {
    canvas.style.transform = "translate(${offsetX}px, ${offsetY}px) scale($scale)"
    canvas.classList.toggle("zoomed-out", scale < LABEL_SCALE)
  }


  private fun viewportSize(): Pair<Double, Double> {
    val element = viewport.getElement() as? HTMLElement
    return (element?.clientWidth?.toDouble() ?: 800.0) to (element?.clientHeight?.toDouble() ?: 600.0)
  }


  /** Zoom so that the point ([x], [y]) in the viewport stays still */
  private fun zoomAt(factor: Double, x: Double, y: Double) {
    val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
    offsetX = x - (x - offsetX) * newScale / scale
    offsetY = y - (y - offsetY) * newScale / scale
    scale = newScale
    applyTransform()
  }


  private fun zoomBy(factor: Double) {
    val (width, height) = viewportSize()
    zoomAt(factor, width / 2, height / 2)
  }


  private fun fit() {
    val (width, height) = viewportSize()
    scale = min(width / layout.width, height / layout.height).coerceIn(MIN_SCALE, MAX_SCALE)
    offsetX = (width - layout.width * scale) / 2
    offsetY = (height - layout.height * scale) / 2
    applyTransform()
  }


  /** Show the first technologies, at the top of the tree, at a readable zoom */
  private fun showTop() {
    val (width, _) = viewportSize()
    val firstRow = layout.positions.values.filter { it.level == 0 }
    val centreX = firstRow.map { it.x }.average().takeIf { !it.isNaN() } ?: (layout.width / 2)
    scale = 0.75
    offsetX = width / 2 - centreX * scale
    offsetY = 24.0 - (firstRow.minOfOrNull { it.y } ?: 0.0) * scale + NODE_HEIGHT / 2 * scale
    applyTransform()
  }


  private fun centreOn(name: String) {
    val position = layout.positions[name] ?: return
    val (width, height) = viewportSize()
    if (scale < 0.6) scale = 1.0
    offsetX = width / 2 - position.x * scale
    offsetY = height / 2 - position.y * scale
    applyTransform()
  }


  private fun initPanZoom(element: HTMLElement) {
    val pointers = mutableMapOf<Int, Pair<Double, Double>>()
    var dragged = false
    var pinchDistance = 0.0

    fun local(event: MouseEvent): Pair<Double, Double> {
      val rect = element.getBoundingClientRect()
      return (event.clientX - rect.left) to (event.clientY - rect.top)
    }

    element.asDynamic().addEventListener("wheel", { event: WheelEvent ->
      event.preventDefault()
      // trackpads send small, frequent deltas; mouse wheels send ~100 per notch
      val delta = if (event.deltaMode == 1) event.deltaY * 33 else event.deltaY
      val (x, y) = local(event)
      zoomAt(exp(-delta * 0.0015), x, y)
    }, js("({passive: false})"))

    element.addEventListener("pointerdown", { event ->
      event as PointerEvent
      if (event.button.toInt() != 0) return@addEventListener
      pointers[event.pointerId] = local(event)
      if (pointers.size == 1) dragged = false
      if (pointers.size == 2) {
        val (a, b) = pointers.values.toList()
        pinchDistance = hypot(a.first - b.first, a.second - b.second)
      }
    })

    element.addEventListener("pointermove", { event ->
      event as PointerEvent
      val previous = pointers[event.pointerId] ?: return@addEventListener
      val current = local(event)
      pointers[event.pointerId] = current
      if (pointers.size == 1) {
        val dx = current.first - previous.first
        val dy = current.second - previous.second
        if (!dragged && abs(dx) + abs(dy) < 3) {
          pointers[event.pointerId] = previous // not a drag yet
          return@addEventListener
        }
        if (!dragged) element.setPointerCapture(event.pointerId)
        dragged = true
        offsetX += dx
        offsetY += dy
        applyTransform()
      } else if (pointers.size == 2) {
        dragged = true
        val (a, b) = pointers.values.toList()
        val distance = hypot(a.first - b.first, a.second - b.second)
        if (pinchDistance > 0) zoomAt(distance / pinchDistance, (a.first + b.first) / 2, (a.second + b.second) / 2)
        pinchDistance = distance
      }
    })

    val release = { event: Event ->
      pointers.remove((event as PointerEvent).pointerId)
      pinchDistance = 0.0
      if (pointers.size == 1) dragged = true // don't treat the end of a pinch as a click
    }
    element.addEventListener("pointerup", release)
    element.addEventListener("pointercancel", release)

    element.addEventListener("click", { event ->
      if (dragged) return@addEventListener
      val node = (event.target as? Element)?.closest("[data-tech]")
      select(node?.getAttribute("data-tech"))
    })

    element.addEventListener("dblclick", { event ->
      val node = (event.target as? Element)?.closest("[data-tech]") ?: return@addEventListener
      node.getAttribute("data-tech")?.let { detail.show(it) }
    })
  }

  //endregion
}
