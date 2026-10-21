package dev.adamko.kafkatorio.webmap.services

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapBuilding
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import dev.adamko.kafkatorio.webmap.config.jsonMapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import io.kvision.rest.RestClient
import io.kvision.rest.call
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await

object EventsServerClient :
  CoroutineScope by CoroutineScope(Dispatchers.Default + SupervisorJob()) {

  private val restClient = RestClient()


  /** Fetch all possible server IDs. */
  suspend fun serverIds(): List<FactorioServerId> =
    restClient
      .call<List<FactorioServerId>>(window.location.origin + "/kafkatorio/data/servers")
      .await()


  /** Fetch the resource patches (ore fields, oil fields) on a surface. */
  suspend fun resourcePatches(serverId: FactorioServerId, surfaceIndex: Int): List<ResourcePatch> {
    val url = "${window.location.origin}/kafkatorio/data/servers/${serverId.id}/map/resources/s$surfaceIndex/patches"
    val json = window.fetch(url).await().text().await()
    return jsonMapper.decodeFromString(ListSerializer(ResourcePatch.serializer()), json)
  }


  /** Fetch the buildings whose centre is within an area. */
  suspend fun buildings(
    serverId: FactorioServerId,
    surfaceIndex: Int,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
  ): List<MapBuilding> {
    val url = "${window.location.origin}/kafkatorio/data/servers/${serverId.id}/map/buildings/s$surfaceIndex" +
        "?left=$left&top=$top&right=$right&bottom=$bottom"
    val json = window.fetch(url).await().text().await()
    return jsonMapper.decodeFromString(ListSerializer(MapBuilding.serializer()), json)
  }


  /**
   * Fetch the size and display name of each building, by entity name.
   *
   * These are exported from Factorio separately (see `docs/kafkatorio-platform/building-icons`),
   * so they might not exist.
   */
  suspend fun buildingInfo(): Map<String, BuildingInfo> {
    val response = window.fetch("${window.location.origin}/kafkatorio/icons/buildings.json").await()
    if (!response.ok) return emptyMap()
    val json = response.text().await()
    return jsonMapper.decodeFromString(MapSerializer(String.serializer(), BuildingInfo.serializer()), json)
  }
}


/** The size (in tiles) and display name of a building */
@Serializable
data class BuildingInfo(
  val w: Int,
  val h: Int,
  val name: String,
)
