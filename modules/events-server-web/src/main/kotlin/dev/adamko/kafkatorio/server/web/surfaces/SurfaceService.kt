package dev.adamko.kafkatorio.server.web.surfaces

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.topicName
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapSurface
import dev.adamko.kafkatorio.schema.common.ServerMapTileLayer
import dev.adamko.kafkatorio.schema.common.ServerMapTilePngFilename
import dev.adamko.kafkatorio.schema.common.SurfaceIndex
import dev.adamko.kafkatorio.schema.packets.SurfaceUpdate
import dev.adamko.kafkatorio.server.web.kafka.followTopic
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory


/** Keeps the names of the surfaces, so the web map can list them. */
class SurfaceService(
  private val appProps: ApplicationProperties,
) {

  private val surfaceNames = ConcurrentHashMap<Pair<FactorioServerId, SurfaceIndex>, String>()


  /** The mod regularly sends the name of every surface. */
  suspend fun consume(): Unit = followTopic(
    appProps,
    SurfaceUpdate::class.topicName,
    clientId = "kafkatorio.events-server-web.surfaces",
  ) { serverId, data ->
    if (data is SurfaceUpdate) surfaceNames[serverId to data.index] = data.name
  }


  /** The surfaces that have a map, sorted by index. */
  fun surfaces(serverId: FactorioServerId): List<MapSurface> =
    surfaceNames
      .filterKeys { (id, index) -> id == serverId && hasTerrain(serverId, index) }
      .map { (key, name) -> MapSurface(index = key.second, name = name) }
      .sortedBy { it.index.toString().toInt() }


  /** Surfaces without terrain tiles (e.g. surfaces that only exist for mod internals) can't be shown. */
  private fun hasTerrain(serverId: FactorioServerId, surfaceIndex: SurfaceIndex): Boolean {
    // the tile files are in `.../s{surfaceIndex}/z{zoom}/x{x}/y{y}.png`
    val tile = ServerMapTilePngFilename(serverId, ServerMapTileLayer.TERRAIN, surfaceIndex, 0, 0, 0)
    val surfaceDir = appProps.serverDataDir.resolve(tile.value).parent.parent.parent
    return surfaceDir.isDirectory()
  }
}
