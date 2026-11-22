package dev.adamko.kafkatorio.webmap.research

import dev.adamko.kafkatorio.webmap.services.TechnologyInfo
import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h3
import io.kvision.html.image
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import kotlinx.browser.document
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.events.KeyboardEvent


/** Display names of items and recipes, from the exported prototype data */
internal class Names(
  val items: Map<String, String>,
  val recipes: Map<String, String>,
) {
  fun item(name: String) = items[name] ?: name
  fun recipe(name: String) = recipes[name] ?: name
}


/**
 * A pop-up with everything about one technology: its cost, prerequisites, what it leads to, and
 * what it unlocks.
 *
 * @param[treeLink] the link to show a technology in the tree, or `null` to not show the link
 * @param[onShow] called whenever a technology is shown, including ones opened from the pop-up
 */
internal class TechnologyDetail(
  page: Container,
  private val model: ResearchModel,
  private val names: Names,
  private val treeLink: ((String) -> String)? = null,
  private val onShow: (String) -> Unit = {},
) {

  private val overlay: Div = page.div(className = "research-detail-overlay") {
    visible = false
    onClick { event -> if (event.target == getElement()) close() }
  }

  init {
    document.addEventListener("keydown", { event ->
      if ((event as KeyboardEvent).key == "Escape") close()
    })
  }

  val isOpen: Boolean get() = overlay.visible


  fun close() {
    overlay.visible = false
    overlay.removeAll()
  }


  fun show(name: String) {
    val tech = model.technologies[name] ?: return
    val status = model.status[name] ?: TechnologyStatus.LOCKED
    onShow(name)
    overlay.removeAll()
    overlay.div(className = "research-detail tech-${status.name.lowercase()}") {
      button("×", className = "research-detail-close") {
        title = "Close"
        onClick { close() }
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
          treeLink?.let { href ->
            div(className = "research-detail-links") {
              link("Show in tree", href(name), className = "tech-link") {
                setAttribute("data-navigo", "true")
              }
            }
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
    overlay.visible = true
    if (treeLink != null) dev.adamko.kafkatorio.webmap.routing.SiteRouter.updatePageLinks()
  }


  private fun Container.packList(cost: SciencePackCost) {
    div(className = "pack-list") {
      model.sciencePacks.filter { it in cost }.forEach { pack ->
        div(className = "pack-total") {
          title = names.item(pack)
          packIcon(pack, names)
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
            onClick { show(name) }
          }
        }
      }
    }
  }
}


internal fun Container.packIcon(pack: String, names: Names, small: Boolean = false) {
  image(
    "/kafkatorio/icons/item/$pack.png",
    alt = names.item(pack),
    className = if (small) "pack-icon pack-icon-small" else "pack-icon",
  ) {
    title = names.item(pack)
  }
}


/** The in-game name. Technologies with levels (e.g. `Mining productivity 3`) already include it. */
internal fun TechnologyInfo.displayName(id: String): String = name.ifBlank { id }


/** e.g. `Craft 10 Iron plate`, for technologies researched by doing something */
internal fun TechnologyInfo.triggerDescription(names: Names): String {
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


internal fun formatAmount(amount: Double): String = when {
  amount >= 1_000_000_000             -> "${(amount / 100_000_000).toLong() / 10.0}B"
  amount >= 1_000_000                 -> "${(amount / 100_000).toLong() / 10.0}M"
  amount >= 10_000                    -> "${(amount / 100).toLong() / 10.0}k"
  amount == kotlin.math.floor(amount) -> amount.toLong().toString()
  else                                -> "${(amount * 10).toLong() / 10.0}"
}
