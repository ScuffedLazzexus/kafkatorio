package dev.adamko.kafkatorio.webmap

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import dev.adamko.kafkatorio.schema.common.ServerMapTileLayer
import dev.adamko.kafkatorio.schema.common.ServerMapTilePngFilename
import dev.adamko.kafkatorio.schema.packets.EventServerPacket
import dev.adamko.kafkatorio.webmap.externals.TileOnLoadFn
import dev.adamko.kafkatorio.webmap.externals.leaflet
import dev.adamko.kafkatorio.webmap.externals.tileOnLoad
import dev.adamko.kafkatorio.webmap.services.EventsServerClient
import dev.adamko.kafkatorio.webmap.services.WebsocketService
import dev.adamko.kafkatorio.webmap.services.latLng
import io.kvision.maps.Maps
import io.kvision.maps.Maps.Companion.L
import io.kvision.maps.externals.leaflet.DoneCallback
import io.kvision.maps.externals.leaflet.control.Control
import io.kvision.maps.externals.leaflet.control.Layers
import io.kvision.maps.externals.leaflet.control.set
import io.kvision.maps.externals.leaflet.geo.CRS
import io.kvision.maps.externals.leaflet.geo.LatLng
import io.kvision.maps.externals.leaflet.layer.LayerGroup
import io.kvision.maps.externals.leaflet.layer.tile.TileLayer
import io.kvision.maps.externals.leaflet.layer.vector.CircleMarker
import io.kvision.maps.externals.leaflet.map.LeafletMap
import io.kvision.utils.px
import js.core.jso
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.Image
import org.w3c.dom.asList
import org.w3c.fetch.NO_CORS
import org.w3c.fetch.RELOAD
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestInit
import org.w3c.fetch.RequestMode
import kotlin.time.Duration.Companion.seconds


class FactorioMap(
  private val serverId: FactorioServerId,
) {

  private val coroutineScope: CoroutineScope = CoroutineScope(
    Dispatchers.Default
        + SupervisorJob(rootJob)
        + CoroutineName("FactorioMap")
  )

  val kvMap: Maps = Maps {
    minHeight = 800.px
    minWidth = 800.px
//    width = 100.vh
//    height = 100.vh
    margin = 10.px
  }

  /** The surface that's shown. Nauvis is surface 1. */
  private var surfaceIndex: Int = 1

  private val terrainTileLayer: TileLayer<*> = createServerTileLayer(ServerMapTileLayer.TERRAIN)
  private val resourcesTileLayer: TileLayer<*> = createServerTileLayer(ServerMapTileLayer.RESOURCE)
  private val buildingsTileLayer: TileLayer<*> = createServerTileLayer(ServerMapTileLayer.BUILDING)

  val playerIconsLayer: LayerGroup = LayerGroup()

  private val resourcePatchesLayer: LayerGroup = LayerGroup()

  private val buildingIconsLayer = BuildingIconsLayer(serverId, coroutineScope)

  /** Resource display names (e.g. `se-iridium-ore` -> `Iridite`), if they've been exported */
  private var resourceNames: Map<String, String>? = null

  /** Player markers, and the surface each player is on */
  private val playerMarkers = mutableMapOf<CircleMarker, Int?>()

  private val surfaceSelect = document.createElement("select") as HTMLSelectElement

  init {

    val baseLayers: Control.LayersObject = jso {
      set("Terrain", terrainTileLayer)
    }

    val overlays: Control.LayersObject = jso {
      set("Players", playerIconsLayer)
      set("Resource patches", resourcePatchesLayer)
      set("Resources", resourcesTileLayer)
      set("Buildings", buildingsTileLayer)
      set("Building icons", buildingIconsLayer.layer)
    }

    val layersControl = Layers(
      baseLayers = baseLayers,
      overlays = overlays,
      options = jso {
        sortLayers = true
      }
    )

    kvMap.configureLeafletMap {
      setView(center = LatLng(0, 0), zoom = 0)

      L.scale {
        metric = true
        imperial = false
      }.addTo(this)

      options.crs = CRS.Simple
      options.zoomSnap = 0
      options.zoomDelta = 0.1
//    options.fadeAnimation = false
      addControl(layersControl)
      addSurfaceControl(this)

      terrainTileLayer.addTo(this)
      resourcesTileLayer.addTo(this)
      buildingsTileLayer.addTo(this)

      buildingIconsLayer.layer.addTo(this)
      playerIconsLayer.addTo(this)
      resourcePatchesLayer.addTo(this)

      val map = this
      on("mousemove", { event ->
        buildingIconsLayer.onMouseMove(map, event.asDynamic().latlng.unsafeCast<LatLng>())
      })
    }

    coroutineScope.launch {
      while (true) {
        runCatching { refreshResourcePatches() }
          .onFailure { println("[FactorioMap] failed to refresh resource patches: $it") }
        delay(60.seconds)
      }
    }

    coroutineScope.launch {
      while (true) {
        delay(60.seconds)
        buildingIconsLayer.refresh()
      }
    }

    coroutineScope.launch {
      while (true) {
        runCatching { refreshSurfaces() }
          .onFailure { println("[FactorioMap] failed to refresh surfaces: $it") }
        delay(60.seconds)
      }
    }


    WebsocketService.packetsFlow
      .filterIsInstance<EventServerPacket.ChunkTileSaved>()
      .onEach { tileSavedEvent ->
//        println("[packetsFlow] triggering tile refresh ${tileSavedEvent.filename.value}")
//        siteStateStore.dispatch(SiteAction.EventServerUpdate(tileSavedEvent))
          refreshUpdatedTilePng(tileSavedEvent.filename)
      }.launchIn(coroutineScope)
  }


  suspend fun refreshUpdatedTilePng(
    tilePngFilename: ServerMapTilePngFilename,
  ): Unit = coroutineScope {
    document
      .querySelectorAll("img.leaflet-tile-loaded")
      .asList()
      .filterIsInstance<Image>()
      .filter { img -> tilePngFilename.value in img.src }
      .forEach { img ->
        val imgSrc = img.src

        launch {
          window.fetch(
            imgSrc,
            RequestInit(
              cache = RequestCache.RELOAD,
              mode = RequestMode.NO_CORS,
            )
          ).then {
            window.requestAnimationFrame {
              println("[refreshUpdatedTilePng] updating $imgSrc")
              img.setAttribute(DYNAMIC_RELOAD_ATT, "true")
              img.src = imgSrc
            }
          }.await()
        }
      }
  }


  /** Show a player's marker only if they're on the shown surface */
  fun updatePlayerMarker(previous: CircleMarker?, updated: CircleMarker?, surfaceIndex: Int?) {
    if (previous != null && previous != updated) {
      playerMarkers.remove(previous)
      playerIconsLayer.removeLayer(previous)
    }
    if (updated != null) {
      playerMarkers[updated] = surfaceIndex
      if (surfaceIndex == this.surfaceIndex) {
        if (!playerIconsLayer.hasLayer(updated)) playerIconsLayer.addLayer(updated)
      } else {
        playerIconsLayer.removeLayer(updated)
      }
    }
  }


  /** A drop-down for choosing which surface (e.g. planet) is shown */
  private fun addSurfaceControl(map: LeafletMap) {
    val control = leaflet.control(jso<dynamic> { this.position = "topleft" })
    control.onAdd = { _: dynamic ->
      val container = document.createElement("div") as HTMLElement
      container.className = "leaflet-bar surface-select"
      container.appendChild(surfaceSelect)
      leaflet.DomEvent.disableClickPropagation(container)
      leaflet.DomEvent.disableScrollPropagation(container)
      container
    }
    control.addTo(map)

    surfaceSelect.onchange = {
      surfaceSelect.value.toIntOrNull()?.let { showSurface(it) }
    }
  }


  private suspend fun refreshSurfaces() {
    val surfaces = EventsServerClient.surfaces(serverId)
    // e.g. `nauvis` -> `Nauvis`
    val options = surfaces.map { it.index.toString().toInt() to it.name.replaceFirstChar(Char::uppercase) }
    val current = (0 until surfaceSelect.options.length).map { i ->
      val option = surfaceSelect.options.item(i) as HTMLOptionElement
      option.value.toInt() to option.text
    }
    if (options == current) return

    surfaceSelect.innerHTML = ""
    options.forEach { (index, name) ->
      val option = document.createElement("option") as HTMLOptionElement
      option.value = "$index"
      option.text = name
      surfaceSelect.appendChild(option)
    }
    surfaceSelect.value = "$surfaceIndex"
  }


  private fun showSurface(index: Int) {
    if (index == surfaceIndex) return
    println("[FactorioMap] showing surface $index")
    surfaceIndex = index

    listOf(
      ServerMapTileLayer.TERRAIN to terrainTileLayer,
      ServerMapTileLayer.RESOURCE to resourcesTileLayer,
      ServerMapTileLayer.BUILDING to buildingsTileLayer,
    ).forEach { (layer, tileLayer) ->
      tileLayer.setUrl(tileLayerUrlTemplate(layer), noRedraw = false)
    }

    buildingIconsLayer.showSurface(index)

    resourcePatchesLayer.clearLayers()
    coroutineScope.launch {
      runCatching { refreshResourcePatches() }
        .onFailure { println("[FactorioMap] failed to refresh resource patches: $it") }
    }

    playerMarkers.forEach { (marker, surface) ->
      if (surface == index) playerIconsLayer.addLayer(marker) else playerIconsLayer.removeLayer(marker)
    }
  }


  private suspend fun refreshResourcePatches() {
    val surface = surfaceIndex
    val names = resourceNames
      ?: runCatching { EventsServerClient.resourceNames() }.getOrDefault(emptyMap()).also { resourceNames = it }
    val patches = EventsServerClient.resourcePatches(serverId, surfaceIndex = surface)
      .filter { it.isInfinite || it.tileCount >= MIN_RESOURCE_PATCH_TILES }

    // the surface might have changed while fetching
    if (surface != surfaceIndex) return

    resourcePatchesLayer.clearLayers()
    patches.forEach { patch ->
      val label = L.divIcon {
        className = "resource-patch-label"
        html = "<span>${patch.displayName(names)}<br>${patch.displayAmount()}</span>"
      }
      L.marker(patch.centre.latLng()) {
        icon = label
      }.bindTooltip(patch.tooltip(names)).addTo(resourcePatchesLayer)
    }
  }


  private fun createServerTileLayer(
    layer: ServerMapTileLayer
  ): TileLayer<TileLayer.TileLayerOptions> {

    val urlTemplate: String = tileLayerUrlTemplate(layer)

    val baseTileLayer = L.tileLayer(
      urlTemplate
    ) {
      attribution = "kafkatorio"
      tileSize = 256
      tms = true

      minZoom = -2
      maxZoom = 6

      maxNativeZoom = 0
      minNativeZoom = -1

      noWrap = true
      updateWhenIdle = false
      updateWhenZooming = true
    }

    val currentTileOnLoad: TileOnLoadFn? = baseTileLayer.tileOnLoad

    if (currentTileOnLoad != null) {
      baseTileLayer.tileOnLoad = { done: DoneCallback, tile: HTMLElement ->
        if (!tile.hasAttribute(DYNAMIC_RELOAD_ATT)) {
          currentTileOnLoad(done, tile)
        }
        tile.removeAttribute(DYNAMIC_RELOAD_ATT)
      }
    }

    return baseTileLayer
  }

  private fun tileLayerUrlTemplate(layer: ServerMapTileLayer): String =
    "/kafkatorio/data/servers/${serverId.id}/map/layers/${layer.dir}/s$surfaceIndex/z{z}/x{x}/y{y}.png"

  companion object {
    /** Add this attribute to `<img>` tiles that need to be dynamically refreshed. */
    const val DYNAMIC_RELOAD_ATT = "dynamic-reload"

    /** Hide stray resource tiles that aren't really a patch */
    private const val MIN_RESOURCE_PATCH_TILES = 10
  }
}


/** The in-game name, or e.g. `iron-ore` -> `Iron ore` if the names haven't been exported */
private fun ResourcePatch.displayName(names: Map<String, String>): String =
  names[protoId.name.toString()]
    ?: protoId.name.toString().replace('-', ' ').replaceFirstChar { it.uppercase() }


/** e.g. `2.4M`, or `12 wells` for infinite resources */
private fun ResourcePatch.displayAmount(): String =
  if (isInfinite) wells() else formatAmount(totalAmount)


private fun ResourcePatch.wells(): String = if (tileCount == 1) "1 well" else "$tileCount wells"


private fun ResourcePatch.tooltip(names: Map<String, String>): String = buildString {
  append("${displayName(names)}: ")
  append(if (isInfinite) wells() else "${formatAmount(totalAmount)} in $tileCount tiles")
  val width = (rightBottom.x - leftTop.x).toInt()
  val height = (rightBottom.y - leftTop.y).toInt()
  append(" (${width}x$height, centre [${centre.x.toInt()}, ${centre.y.toInt()}])")
}


private fun formatAmount(amount: Long): String = when {
  amount >= 1_000_000_000 -> "${(amount / 100_000_000) / 10.0}B"
  amount >= 1_000_000     -> "${(amount / 100_000) / 10.0}M"
  amount >= 1_000         -> "${(amount / 100) / 10.0}k"
  else                    -> "$amount"
}
