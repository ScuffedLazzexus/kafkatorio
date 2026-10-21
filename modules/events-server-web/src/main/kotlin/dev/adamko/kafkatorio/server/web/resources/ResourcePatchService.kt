package dev.adamko.kafkatorio.server.web.resources

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.topicName
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapChunkPosition
import dev.adamko.kafkatorio.schema.common.MapEntityPosition
import dev.adamko.kafkatorio.schema.common.PrototypeId
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import dev.adamko.kafkatorio.schema.common.SurfaceIndex
import dev.adamko.kafkatorio.schema.packets.MapChunkResourceUpdate
import dev.adamko.kafkatorio.server.web.kafka.followTopic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor


/**
 * Keeps the latest amount of every resource tile in memory, and groups nearby tiles of the same
 * resource into [ResourcePatch]es.
 *
 * Resource data is small, so instead of a Kafka Streams state store, the whole
 * [MapChunkResourceUpdate] topic is read from the beginning on startup, and then followed.
 */
class ResourcePatchService(
  private val appProps: ApplicationProperties,
) {

  private data class ChunkKey(
    val serverId: FactorioServerId,
    val protoId: PrototypeId,
    val surfaceIndex: SurfaceIndex,
    val chunkPosition: MapChunkPosition,
  )

  private class ChunkResources(
    val amounts: MutableMap<MapEntityPosition, UInt> = mutableMapOf(),
    var isInfinite: Boolean = false,
  )

  private val chunks = ConcurrentHashMap<ChunkKey, ChunkResources>()

  /** Incremented on every update, so cached patches can be invalidated. */
  private val version = AtomicLong(0)

  private data class CachedPatches(val version: Long, val patches: List<ResourcePatch>)

  private val patchCache = ConcurrentHashMap<Pair<FactorioServerId, SurfaceIndex>, CachedPatches>()


  /** Read all resource updates from the beginning of the topic, and keep following it. */
  suspend fun consume(): Unit = followTopic(
    appProps,
    MapChunkResourceUpdate::class.topicName,
    clientId = "kafkatorio.events-server-web.resource-patches",
  ) { serverId, data ->
    if (data is MapChunkResourceUpdate) handleUpdate(serverId, data)
  }


  private fun handleUpdate(serverId: FactorioServerId, update: MapChunkResourceUpdate) {
    val chunkKey = ChunkKey(
      serverId = serverId,
      protoId = update.key.protoId,
      surfaceIndex = update.key.surfaceIndex,
      chunkPosition = update.key.chunkPosition,
    )

    val chunk = chunks.computeIfAbsent(chunkKey) { ChunkResources() }
    synchronized(chunk) {
      // Updates contain the current amount of the tiles that were (re-)scanned, so merge them.
      // Note: depleted tiles aren't reported by the mod, so they keep their last known amount.
      update.amounts.forEach { (position, amount) -> chunk.amounts[position] = amount }
      if (update.initialAmounts.any()) chunk.isInfinite = true
    }
    version.incrementAndGet()
  }


  fun patches(serverId: FactorioServerId, surfaceIndex: SurfaceIndex): List<ResourcePatch> {
    val currentVersion = version.get()
    val cacheKey = serverId to surfaceIndex

    patchCache[cacheKey]?.let { cached ->
      if (cached.version == currentVersion) return cached.patches
    }

    val patches = computePatches(serverId, surfaceIndex)
    patchCache[cacheKey] = CachedPatches(currentVersion, patches)
    return patches
  }


  private fun computePatches(
    serverId: FactorioServerId,
    surfaceIndex: SurfaceIndex,
  ): List<ResourcePatch> {

    // collect all tiles per resource prototype
    val tilesByProto = mutableMapOf<PrototypeId, MutableMap<Long, UInt>>()
    val infiniteProtos = mutableSetOf<PrototypeId>()

    chunks.forEach { (key, chunk) ->
      if (key.serverId != serverId || key.surfaceIndex != surfaceIndex) return@forEach
      val tiles = tilesByProto.getOrPut(key.protoId) { mutableMapOf() }
      synchronized(chunk) {
        if (chunk.isInfinite) infiniteProtos += key.protoId
        chunk.amounts.forEach { (position, amount) ->
          tiles[packTile(floor(position.x).toInt(), floor(position.y).toInt())] = amount
        }
      }
    }

    return tilesByProto.flatMap { (protoId, tiles) ->
      val isInfinite = protoId in infiniteProtos
      // Ore tiles in a patch are (nearly) contiguous, but oil wells are spread out.
      val joinDistance = if (isInfinite) INFINITE_RESOURCE_JOIN_DISTANCE else ORE_JOIN_DISTANCE
      groupIntoPatches(tiles, joinDistance).map { patchTiles ->
        createPatch(protoId, surfaceIndex, isInfinite, patchTiles, tiles)
      }
    }.sortedByDescending { it.tileCount }
  }


  /** Group tiles into connected components, where tiles within [joinDistance] are connected. */
  private fun groupIntoPatches(tiles: Map<Long, UInt>, joinDistance: Int): List<List<Long>> {
    val unvisited = tiles.keys.toMutableSet()
    val patches = mutableListOf<List<Long>>()

    while (unvisited.isNotEmpty()) {
      val start = unvisited.first()
      unvisited.remove(start)

      val patch = mutableListOf(start)
      val queue = ArrayDeque(listOf(start))

      while (queue.isNotEmpty()) {
        val tile = queue.removeFirst()
        val x = unpackX(tile)
        val y = unpackY(tile)
        for (dx in -joinDistance..joinDistance) {
          for (dy in -joinDistance..joinDistance) {
            val neighbour = packTile(x + dx, y + dy)
            if (unvisited.remove(neighbour)) {
              patch += neighbour
              queue += neighbour
            }
          }
        }
      }
      patches += patch
    }
    return patches
  }


  private fun createPatch(
    protoId: PrototypeId,
    surfaceIndex: SurfaceIndex,
    isInfinite: Boolean,
    patchTiles: List<Long>,
    amounts: Map<Long, UInt>,
  ): ResourcePatch {
    val xs = patchTiles.map { unpackX(it) }
    val ys = patchTiles.map { unpackY(it) }

    return ResourcePatch(
      protoId = protoId,
      surfaceIndex = surfaceIndex,
      totalAmount = patchTiles.sumOf { amounts.getValue(it).toLong() },
      tileCount = patchTiles.size,
      isInfinite = isInfinite,
      // +0.5: the centre of the tile
      centre = MapEntityPosition(xs.average() + 0.5, ys.average() + 0.5),
      leftTop = MapEntityPosition(xs.min().toDouble(), ys.min().toDouble()),
      rightBottom = MapEntityPosition(xs.max() + 1.0, ys.max() + 1.0),
    )
  }


  companion object {
    private const val ORE_JOIN_DISTANCE = 2
    private const val INFINITE_RESOURCE_JOIN_DISTANCE = 12

    /** Pack a tile coordinate into a single Long, for fast set lookups. */
    private fun packTile(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun unpackX(packed: Long): Int = (packed shr 32).toInt()
    private fun unpackY(packed: Long): Int = packed.toInt()
  }
}
