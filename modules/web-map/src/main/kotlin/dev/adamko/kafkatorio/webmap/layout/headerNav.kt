package dev.adamko.kafkatorio.webmap.layout

import dev.adamko.kafkatorio.webmap.routing.SiteView
import dev.adamko.kafkatorio.webmap.state.SiteState
import io.kvision.core.Container
import io.kvision.navbar.nav
import io.kvision.navbar.navLink
import io.kvision.navbar.navbar


fun Container.headerNav(state: SiteState) {
  navbar(label = "Kafkatorio", link = SiteView.HOME.path) {
    val serverId = state.serverId ?: return@navbar
    nav {
      navLink("Map", "/servers/${serverId.id}", className = navClass(state.view == SiteView.SERVER)) {
        setAttribute("data-navigo", "true")
      }
      navLink("Research", "/servers/${serverId.id}/research", className = navClass(state.view == SiteView.RESEARCH)) {
        setAttribute("data-navigo", "true")
      }
    }
  }
}

private fun navClass(active: Boolean) = if (active) "active" else null
