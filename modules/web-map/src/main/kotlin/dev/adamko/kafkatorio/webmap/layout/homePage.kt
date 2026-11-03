package dev.adamko.kafkatorio.webmap.layout

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapSurface
import dev.adamko.kafkatorio.webmap.services.EventsServerClient
import dev.adamko.kafkatorio.webmap.state.SiteState
import io.kvision.core.Container
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.image
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


private val homePageScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


fun Container.homePage(state: SiteState) {
  div(className = "home-page") {
    hero()
    servers(state)
    about()
  }
}


private fun Container.hero() {
  div(className = "home-hero") {
    image("/img/kafkatorio-logo.png", alt = "Kafkatorio logo", className = "home-hero-logo")
    div {
      h1("Kafkatorio")
      p("Live maps of Factorio servers, streamed through Apache Kafka.", className = "home-hero-tagline")
    }
  }
}


private fun Container.servers(state: SiteState) {
  div(className = "home-section") {
    h2("Servers")
    val serverIds = state.serverIds
    when {
      serverIds == null    -> p("Loading servers…", className = "home-muted")
      serverIds.isEmpty()  -> p("No servers are sending data yet.", className = "home-muted")
      else                 -> div(className = "server-cards") {
        serverIds.forEach { serverCard(it) }
      }
    }
  }
}


private fun Container.serverCard(serverId: FactorioServerId) {
  link(label = "", url = "/servers/${serverId.id}", className = "server-card") {
    setAttribute("data-navigo", "true")

    mapPreview(serverId)

    div(className = "server-card-body") {
      h3(serverId.id)
      val surfaces = div(className = "server-card-surfaces") {
        span("Loading surfaces…", className = "home-muted")
      }
      span("Open map →", className = "server-card-action")

      homePageScope.launch {
        val loaded = runCatching { EventsServerClient.surfaces(serverId) }.getOrNull()
        surfaces.removeAll()
        surfaces.surfaceChips(loaded)
      }
    }
  }
}


private fun Container.surfaceChips(surfaces: List<MapSurface>?) {
  if (surfaces.isNullOrEmpty()) {
    span("No map data yet", className = "home-muted")
    return
  }
  surfaces.forEach { surface ->
    span(surface.name.replaceFirstChar { it.uppercase() }, className = "surface-chip")
  }
}


/**
 * The centre of the map (1024x1024 Factorio tiles around the origin), from the lowest-zoom map
 * tiles, with the resource and building layers on top of the terrain.
 */
private fun Container.mapPreview(serverId: FactorioServerId) {
  div(className = "server-card-preview") {
    PREVIEW_LAYERS.forEach { layer ->
      div(className = "server-card-preview-layer") {
        for (y in listOf(-1, 0)) {
          for (x in listOf(-1, 0)) {
            image(
              "/kafkatorio/data/servers/${serverId.id}/map/layers/$layer/s1/z-1/x$x/y$y.png",
              alt = "",
            ) {
              // hide missing tiles, instead of showing a broken image
              setAttribute("onerror", "this.style.visibility='hidden'")
              setAttribute("loading", "lazy")
            }
          }
        }
      }
    }
  }
}

private val PREVIEW_LAYERS = listOf("terrain", "resource", "building")


private fun Container.about() {
  div(className = "home-section home-about") {
    h2("About")
    p(
      """
        Kafkatorio is a platform for Factorio mods that need to talk to an external server.
        A <a target="_blank" href="https://mods.factorio.com/mod/kafkatorio-events">Factorio mod</a>
        sends updates from the game to <a target="_blank" href="https://kafka.apache.org/">Apache Kafka</a>,
        where they're processed into the live maps on this site: terrain, resources, buildings and players,
        on every planet and orbit.
      """.trimIndent(),
      rich = true,
    )
    p(
      """
        More on the <a target="_blank" href="https://forums.factorio.com/viewtopic.php?f=190&t=102841">Factorio forum</a>
        and on <a target="_blank" href="https://github.com/adamko-dev/kafkatorio">GitHub</a>.
      """.trimIndent(),
      rich = true,
    )
  }
}
