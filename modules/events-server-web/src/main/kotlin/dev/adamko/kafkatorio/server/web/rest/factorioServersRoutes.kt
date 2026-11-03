package dev.adamko.kafkatorio.server.web.rest

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.PlayerIndex
import dev.adamko.kafkatorio.schema.common.ServerMapTileLayer
import dev.adamko.kafkatorio.schema.common.ServerMapTilePngFilename
import dev.adamko.kafkatorio.schema.common.SurfaceIndex
import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.server.web.config.jsonMapper
import dev.adamko.kafkatorio.server.web.buildings.BuildingService
import dev.adamko.kafkatorio.server.web.resources.ResourcePatchService
import dev.adamko.kafkatorio.server.web.research.ResearchService
import dev.adamko.kafkatorio.server.web.surfaces.SurfaceService
import dev.adamko.kafkatorio.schema.packets.ResearchUpdate
import dev.adamko.kafkatorio.schema.common.MapSurface
import dev.adamko.kafkatorio.schema.common.MapBuilding
import dev.adamko.kafkatorio.schema.common.MapEntityPosition
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import kotlinx.serialization.builtins.ListSerializer
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.EntityTagVersion
import io.ktor.http.content.versions
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LastModifiedVersion
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Routing
import io.ktor.util.combineSafe
import io.ktor.util.pipeline.PipelineContext
import kotlin.io.path.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement


internal fun Routing.factorioServersRoutes(
  appProps: ApplicationProperties,
  resourcePatchService: ResourcePatchService,
  buildingService: BuildingService,
  surfaceService: SurfaceService,
  researchService: ResearchService,
) {

  get<FactorioServers> {
    val serverIds = appProps.kafkatorioServers.values.toSet().sortedBy { it.id }
    val serverIdsJson = jsonMapper.encodeToJsonElement(serverIds)
    call.respond(serverIdsJson)
  }

  get<FactorioServer.MapData.Surfaces> { request ->
    val surfaces = surfaceService.surfaces(request.mapData.server.factorioServerId)
    call.respond(jsonMapper.encodeToJsonElement(ListSerializer(MapSurface.serializer()), surfaces))
  }

  get<FactorioServer.Research> { request ->
    val research = researchService.research(request.server.factorioServerId)
    if (research == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respond(jsonMapper.encodeToJsonElement(ResearchUpdate.serializer(), research))
    }
  }

  get<FactorioServer.MapData.Tile> { tile ->
    handleTileRequest(tile, appProps)
  }

  get<FactorioServer.MapData.ResourcePatches> { request ->
    val patches = resourcePatchService.patches(request.mapData.server.factorioServerId, request.surfaceIndex)
    call.respond(jsonMapper.encodeToJsonElement(ListSerializer(ResourcePatch.serializer()), patches))
  }

  get<FactorioServer.MapData.Buildings> { request ->
    val buildings = buildingService.buildings(
      serverId = request.mapData.server.factorioServerId,
      surfaceIndex = request.surfaceIndex,
      leftTop = MapEntityPosition(request.left, request.top),
      rightBottom = MapEntityPosition(request.right, request.bottom),
    )
    call.respond(jsonMapper.encodeToJsonElement(ListSerializer(MapBuilding.serializer()), buildings))
  }
}


private suspend fun PipelineContext<Unit, ApplicationCall>.handleTileRequest(
  tile: FactorioServer.MapData.Tile,
  appProps: ApplicationProperties,
) {

  val tileFilename = ServerMapTilePngFilename(
    tile.serverId,
    tile.layer,
    tile.surfaceIndex,
    tile.zoomLevel,
    tile.chunkX,
    tile.chunkY,
  )

  val tileFile = appProps.serverDataDir.combineSafe(Path(tileFilename.value))

  if (tileFile.exists()) {
    call.respondFile(tileFile) {
      versions = versions + listOf(
        EntityTagVersion(tileFile.lastModified().hashCode().toString()),
        LastModifiedVersion(tileFile.lastModified()),
      )
    }
  } else {
    // It's not a client or server error to request a non-existent tile, it just doesn't exist
    // (yet). So return 304.
    call.respond(HttpStatusCode.NotModified)
  }
}


@Serializable
@Resource("kafkatorio/data/servers")
private class FactorioServers


@Serializable
@Resource("kafkatorio/data/servers/{factorioServerId}")
data class FactorioServer(
  val factorioServerId: FactorioServerId,
) {

  @Serializable
  @Resource("map")
  data class MapData(
    val server: FactorioServer,
  ) {

    @Serializable
    @Resource("layers/{layer}/s{surfaceIndex}/z{zoomLevel}/x{chunkX}/y{chunkY}.png")
    data class Tile(
      val mapData: MapData,

      val layer: ServerMapTileLayer,
      val surfaceIndex: SurfaceIndex,
      val zoomLevel: Int,
      val chunkX: Int,
      val chunkY: Int,
    )

    @Serializable
    @Resource("surfaces")
    data class Surfaces(
      val mapData: MapData,
    )

    @Serializable
    @Resource("resources/s{surfaceIndex}/patches")
    data class ResourcePatches(
      val mapData: MapData,
      val surfaceIndex: SurfaceIndex,
    )

    @Serializable
    @Resource("buildings/s{surfaceIndex}")
    data class Buildings(
      val mapData: MapData,
      val surfaceIndex: SurfaceIndex,
      // the area, as query parameters
      val left: Double,
      val top: Double,
      val right: Double,
      val bottom: Double,
    )
  }

  /** The research state of the players' force */
  @Serializable
  @Resource("research")
  data class Research(
    val server: FactorioServer,
  )

  @Serializable
  @Resource("players")
  data class Players(
    val server: FactorioServer,
  )

  @Serializable
  @Resource("{playerId}")
  data class Player(
    val server: FactorioServer,
    val playerIndex: PlayerIndex,
  )
}


private val FactorioServer.MapData.Tile.serverId: FactorioServerId
  get() = mapData.server.factorioServerId
