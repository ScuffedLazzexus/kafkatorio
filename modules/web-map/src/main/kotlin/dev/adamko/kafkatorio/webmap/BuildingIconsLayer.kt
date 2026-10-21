package dev.adamko.kafkatorio.webmap

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapBuilding
import dev.adamko.kafkatorio.webmap.externals.leaflet
import dev.adamko.kafkatorio.webmap.services.BuildingInfo
import dev.adamko.kafkatorio.webmap.services.EventsServerClient
import io.kvision.maps.externals.leaflet.geo.LatLng
import io.kvision.maps.externals.leaflet.layer.Layer
import io.kvision.maps.externals.leaflet.map.LeafletMap
import js.core.jso
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.Image


/**
 * Draws building icons at their real size, onto canvas tiles.
 *
 * Bases have many thousands of buildings, so drawing them onto a few canvases is much faster than
 * adding a marker per building.
 *
 * Hovering over a building shows its name and status.
 */
class BuildingIconsLayer(
  private val serverId: FactorioServerId,
  private val coroutineScope: CoroutineScope,
) {

  private val buildingInfo: Deferred<Map<String, BuildingInfo>> = coroutineScope.async {
    runCatching { EventsServerClient.buildingInfo() }
      .onFailure { println("[BuildingIconsLayer] failed to load building info: $it") }
      .getOrDefault(emptyMap())
      .also { loadedBuildingInfo = it }
  }

  /** [buildingInfo], once loaded (for use outside of coroutines) */
  private var loadedBuildingInfo: Map<String, BuildingInfo> = emptyMap()

  /** Recipe display names, by recipe name */
  private val recipeNames: Deferred<Map<String, String>> = coroutineScope.async {
    runCatching { EventsServerClient.recipeNames() }
      .onFailure { println("[BuildingIconsLayer] failed to load recipe names: $it") }
      .getOrDefault(emptyMap())
      .also { loadedRecipeNames = it }
  }

  /** [recipeNames], once loaded (for use outside of coroutines) */
  private var loadedRecipeNames: Map<String, String> = emptyMap()

  private val icons = mutableMapOf<String, Deferred<Image?>>()

  /** The surface that's shown. Nauvis is surface 1. */
  private var surfaceIndex: Int = 1

  /** The buildings on loaded tiles, by [cellKey], for finding the hovered building. */
  private val hoverIndex = mutableMapOf<String, MutableMap<String, MapBuilding>>()

  /** A Leaflet `GridLayer`, which draws each tile with [drawTile] */
  val layer: Layer<*> = leaflet.gridLayer(jso<dynamic> {
    this.tileSize = TILE_SIZE
    this.minZoom = MIN_ZOOM
    this.maxZoom = 6
    this.noWrap = true
  }).unsafeCast<Layer<*>>()

  init {
    layer.asDynamic().createTile = { coords: dynamic, done: (Any?, HTMLCanvasElement) -> Unit ->
      val canvas = document.createElement("canvas") as HTMLCanvasElement
      canvas.width = TILE_SIZE
      canvas.height = TILE_SIZE
      coroutineScope.launch {
        runCatching { drawTile(canvas, coords.x as Int, coords.y as Int, coords.z as Int) }
          .onFailure { println("[BuildingIconsLayer] failed to draw tile: $it") }
        done(null, canvas)
      }
      canvas
    }
  }


  /** Re-draw the visible tiles, without clearing them first (which would make them flicker). */
  fun refresh() {
    val tiles: dynamic = layer.asDynamic()._tiles ?: return
    js("Object").values(tiles).unsafeCast<Array<dynamic>>().forEach { tile ->
      val canvas = tile.el.unsafeCast<HTMLCanvasElement>()
      val coords = tile.coords
      coroutineScope.launch {
        runCatching { drawTile(canvas, coords.x as Int, coords.y as Int, coords.z as Int) }
          .onFailure { println("[BuildingIconsLayer] failed to refresh tile: $it") }
      }
    }
  }


  /** Switch to another surface, and re-draw all tiles */
  fun showSurface(index: Int) {
    surfaceIndex = index
    hoverIndex.clear()
    layer.asDynamic().redraw()
  }


  /** Show a tooltip for the building under the mouse */
  fun onMouseMove(map: LeafletMap, latLng: LatLng) {
    if (!map.hasLayer(layer) || map.getZoom().toDouble() < MIN_ZOOM) {
      map.closeTooltip(hoverTooltip)
      return
    }
    // CRS.Simple: lng is the Factorio x, -lat is the Factorio y
    val x = latLng.lng.toDouble()
    val y = -latLng.lat.toDouble()
    val info = loadedBuildingInfo

    val hovered = (-1..1).asSequence().flatMap { dx ->
      (-1..1).asSequence().mapNotNull { dy ->
        hoverIndex[cellKey(x + dx * CELL_SIZE, y + dy * CELL_SIZE)]
      }
    }.flatMap { it.values }
      .filter { building ->
        val (w, h) = building.footprint(info)
        x >= building.position.x - w / 2.0 && x < building.position.x + w / 2.0
            && y >= building.position.y - h / 2.0 && y < building.position.y + h / 2.0
      }
      // prefer small buildings, e.g. an inserter next to an assembler
      .minByOrNull { it.iconSize(info) }

    if (hovered == null) {
      map.closeTooltip(hoverTooltip)
    } else {
      hoverTooltip.setLatLng(latLng)
      hoverTooltip.setContent(hovered.tooltip(info, loadedRecipeNames))
      if (!map.hasLayer(hoverTooltip)) map.openTooltip(hoverTooltip)
    }
  }

  private val hoverTooltip: dynamic = leaflet.tooltip(jso<dynamic> {
    this.direction = "top"
    this.offset = leaflet.point(0, -8)
  })


  private suspend fun drawTile(canvas: HTMLCanvasElement, tileX: Int, tileY: Int, zoom: Int) {
    // CRS.Simple: at zoom z, 1 Factorio tile is 2^z pixels
    val scale = 2.0.pow(zoom)
    val tileSizeInMap = TILE_SIZE / scale
    val left = tileX * tileSizeInMap
    val top = tileY * tileSizeInMap

    val surface = surfaceIndex
    // also get buildings that are centred on a neighbouring tile, but overlap this one
    val buildings = EventsServerClient.buildings(
      serverId,
      surfaceIndex = surface,
      left = left - MAX_BUILDING_SIZE,
      top = top - MAX_BUILDING_SIZE,
      right = left + tileSizeInMap + MAX_BUILDING_SIZE,
      bottom = top + tileSizeInMap + MAX_BUILDING_SIZE,
    )
    val info = buildingInfo.await()
    val recipes = recipeNames.await()

    // the surface might have changed while fetching
    if (surface != surfaceIndex) return

    updateHoverIndex(left, top, left + tileSizeInMap, top + tileSizeInMap, buildings)

    // load the icons before drawing, so the canvas is updated in one go
    val buildingIcons = buildings.associateWith { building ->
      building.protoId.name.toString().takeIf { it in info }?.let { icon("entity", it) }
    }.mapValues { (_, icon) -> icon?.await() }

    // like Factorio's 'alt mode', show the recipe on crafting machines, if it's big enough to see
    val showRecipes = zoom >= RECIPE_MIN_ZOOM
    val recipeIcons = if (!showRecipes) emptyMap() else buildings.associateWith { building ->
      building.recipe?.takeIf { it in recipes }?.let { icon("recipe", it) }
    }.mapValues { (_, icon) -> icon?.await() }

    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.clearRect(0.0, 0.0, TILE_SIZE.toDouble(), TILE_SIZE.toDouble())

    // draw big buildings first, so small ones (like inserters) are on top
    buildings.sortedByDescending { it.iconSize(info) }.forEach { building ->
      val centreX = (building.position.x - left) * scale
      val centreY = (building.position.y - top) * scale

      // icons are square, so show the real footprint of rectangular buildings underneath
      val (w, h) = building.footprint(info)
      if (w != h) {
        ctx.fillStyle = "rgba(0, 0, 0, 0.35)"
        ctx.fillRect(centreX - w * scale / 2, centreY - h * scale / 2, w * scale, h * scale)
      }

      val size = building.iconSize(info) * scale
      val icon = buildingIcons[building]
      if (icon != null) {
        ctx.drawImage(icon, centreX - size / 2, centreY - size / 2, size, size)
      } else {
        // no icon - show the outline of the building
        ctx.fillStyle = "rgba(160, 160, 160, 0.6)"
        ctx.fillRect(centreX - size / 2, centreY - size / 2, size, size)
      }

      val recipeIcon = recipeIcons[building]
      if (recipeIcon != null) {
        val recipeSize = size * 0.6
        ctx.fillStyle = "rgba(0, 0, 0, 0.6)"
        ctx.beginPath()
        ctx.arc(centreX, centreY, recipeSize * 0.6, 0.0, 2 * PI)
        ctx.fill()
        ctx.drawImage(recipeIcon, centreX - recipeSize / 2, centreY - recipeSize / 2, recipeSize, recipeSize)
      }
    }
  }


  /** Load an icon from `/kafkatorio/icons/{type}/{name}.png` */
  private fun icon(type: String, name: String): Deferred<Image?> {
    return icons.getOrPut("$type/$name") {
      val deferred = CompletableDeferred<Image?>()
      val img = Image()
      img.onload = { deferred.complete(img) }
      img.onerror = { _, _, _, _, _ -> deferred.complete(null) }
      img.src = "/kafkatorio/icons/$type/$name.png"
      deferred
    }
  }


  private fun updateHoverIndex(
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
    buildings: List<MapBuilding>,
  ) {
    // remove buildings that were in this area, in case they've been removed since
    hoverIndex.values.forEach { cell ->
      cell.values.removeAll { b ->
        b.position.x >= left && b.position.x < right && b.position.y >= top && b.position.y < bottom
      }
    }
    buildings.forEach { building ->
      hoverIndex.getOrPut(cellKey(building.position.x, building.position.y)) { mutableMapOf() }[
        "${building.position.x},${building.position.y}"
      ] = building
    }
  }


  companion object {
    private const val TILE_SIZE = 256

    /** At lower zoom levels icons are too small to see. (At zoom 2, 1 Factorio tile is 4 pixels.) */
    private const val MIN_ZOOM = 2

    /** Recipe icons are too small to see at lower zoom levels */
    private const val RECIPE_MIN_ZOOM = 4

    /** The largest building, in tiles, so buildings that overlap tile edges are included */
    private const val MAX_BUILDING_SIZE = 16.0

    /** The size of the [hoverIndex] cells, in Factorio tiles */
    private const val CELL_SIZE = 16.0

    private fun cellKey(x: Double, y: Double): String =
      "${floor(x / CELL_SIZE).toInt()},${floor(y / CELL_SIZE).toInt()}"
  }
}


/** Icons are drawn as squares, fitting inside the building. */
private fun MapBuilding.iconSize(info: Map<String, BuildingInfo>): Int {
  val (w, h) = footprint(info)
  return max(1, min(w, h))
}


/** The width and height in tiles, rotated if the building faces east or west */
private fun MapBuilding.footprint(info: Map<String, BuildingInfo>): Pair<Int, Int> {
  val building = info[protoId.name.toString()] ?: return 1 to 1
  val isSideways = direction?.toInt() == DIRECTION_EAST || direction?.toInt() == DIRECTION_WEST
  return if (isSideways) building.h to building.w else building.w to building.h
}

/** `defines.direction.east` - Factorio 2.0 has 16 directions, clockwise from north (0) */
private const val DIRECTION_EAST = 4
private const val DIRECTION_WEST = 12


private fun MapBuilding.tooltip(
  info: Map<String, BuildingInfo>,
  recipeNames: Map<String, String>,
): String = buildString {
  val name = protoId.name.toString()
  append("<b>${info[name]?.name ?: name}</b>")
  recipe?.let { append("<br>Recipe: ${recipeNames[it] ?: it}") }
  status?.let { append("<br>${it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)}") }
  health?.let { append("<br>Health: ${it.toInt()}") }
  append("<br>[${floor(position.x).toInt()}, ${floor(position.y).toInt()}]")
}
