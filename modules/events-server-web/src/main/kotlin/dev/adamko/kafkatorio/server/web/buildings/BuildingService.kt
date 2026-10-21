package dev.adamko.kafkatorio.server.web.buildings

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.topicName
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapBuilding
import dev.adamko.kafkatorio.schema.common.MapEntityPosition
import dev.adamko.kafkatorio.schema.common.SurfaceIndex
import dev.adamko.kafkatorio.schema.packets.MapChunkEntityUpdate
import dev.adamko.kafkatorio.server.web.kafka.followTopic
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor


/**
 * Keeps the latest state of every building in memory, so the web map can draw them as icons.
 *
 * Like [dev.adamko.kafkatorio.server.web.resources.ResourcePatchService], the whole
 * [MapChunkEntityUpdate] topic is read from the beginning on startup, and then followed.
 */
class BuildingService(
  private val appProps: ApplicationProperties,
) {

  private data class ChunkKey(
    val serverId: FactorioServerId,
    val surfaceIndex: SurfaceIndex,
    val chunkX: Int,
    val chunkY: Int,
  )

  /** Buildings per chunk, by position */
  private val chunks = ConcurrentHashMap<ChunkKey, ConcurrentHashMap<MapEntityPosition, MapBuilding>>()


  /** Read all building updates from the beginning of the topic, and keep following it. */
  suspend fun consume(): Unit = followTopic(
    appProps,
    MapChunkEntityUpdate::class.topicName,
    clientId = "kafkatorio.events-server-web.buildings",
  ) { serverId, data ->
    if (data is MapChunkEntityUpdate) handleUpdate(serverId, data)
  }


  private fun handleUpdate(serverId: FactorioServerId, update: MapChunkEntityUpdate) {
    // ghosts are planned buildings, not built ones
    if (update.key.protoId.type.toString() in IGNORED_TYPES) return

    // The mod groups updates per prototype and chunk, so an update only says which events
    // happened, not which event affected which building. If the buildings were only mined,
    // then they're gone. (If they were built and mined in the same update, then keep them.)
    val eventNames = update.events?.keys?.map { it.toString() }.orEmpty()
    val isRemoved = eventNames.isNotEmpty() && eventNames.all { it in REMOVAL_EVENTS }

    update.entities().forEach { entity ->
      val chunkKey = ChunkKey(
        serverId = serverId,
        surfaceIndex = update.key.surfaceIndex,
        chunkX = floor(entity.position.x / CHUNK_SIZE).toInt(),
        chunkY = floor(entity.position.y / CHUNK_SIZE).toInt(),
      )
      if (isRemoved) {
        chunks[chunkKey]?.remove(entity.position)
      } else {
        chunks.computeIfAbsent(chunkKey) { ConcurrentHashMap() }[entity.position] = MapBuilding(
          protoId = entity.protoId,
          position = entity.position,
          status = entity.status,
          health = entity.health,
          unitNumber = entity.unitNumber,
          direction = entity.direction,
          recipe = entity.recipe,
        )
      }
    }
  }


  /** Get the buildings whose centre is within an area (inclusive left/top, exclusive right/bottom). */
  fun buildings(
    serverId: FactorioServerId,
    surfaceIndex: SurfaceIndex,
    leftTop: MapEntityPosition,
    rightBottom: MapEntityPosition,
  ): List<MapBuilding> {
    val chunkX0 = floor(leftTop.x / CHUNK_SIZE).toInt()
    val chunkY0 = floor(leftTop.y / CHUNK_SIZE).toInt()
    val chunkX1 = floor(rightBottom.x / CHUNK_SIZE).toInt()
    val chunkY1 = floor(rightBottom.y / CHUNK_SIZE).toInt()

    val result = mutableListOf<MapBuilding>()
    for (chunkX in chunkX0..chunkX1) {
      for (chunkY in chunkY0..chunkY1) {
        val chunk = chunks[ChunkKey(serverId, surfaceIndex, chunkX, chunkY)] ?: continue
        chunk.values.filterTo(result) { building ->
          building.position.x >= leftTop.x && building.position.x < rightBottom.x
              && building.position.y >= leftTop.y && building.position.y < rightBottom.y
        }
        if (result.size >= MAX_BUILDINGS) return result
      }
    }
    return result
  }


  companion object {
    private const val CHUNK_SIZE = 32.0

    /** Limit the response size, if a client requests a huge area */
    private const val MAX_BUILDINGS = 50_000

    private val IGNORED_TYPES = setOf("entity-ghost", "tile-ghost")

    private val REMOVAL_EVENTS = setOf(
      "on_player_mined_entity",
      "on_robot_mined_entity",
      "on_entity_died",
    )
  }
}
