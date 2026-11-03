package dev.adamko.kafkatorio.webmap.services

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.MapBuilding
import dev.adamko.kafkatorio.schema.common.MapSurface
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import dev.adamko.kafkatorio.schema.packets.ResearchUpdate
import dev.adamko.kafkatorio.webmap.config.jsonMapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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


  /** Fetch the surfaces that have a map. */
  suspend fun surfaces(serverId: FactorioServerId): List<MapSurface> {
    val url = "${window.location.origin}/kafkatorio/data/servers/${serverId.id}/map/surfaces"
    val json = window.fetch(url).await().text().await()
    return jsonMapper.decodeFromString(ListSerializer(MapSurface.serializer()), json)
  }


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


  /** Fetch the display name of each recipe, by recipe name (exported like [buildingInfo]). */
  suspend fun recipeNames(): Map<String, String> = displayNames("recipes.json")


  /** Fetch the display name of each resource, by resource name (exported like [buildingInfo]). */
  suspend fun resourceNames(): Map<String, String> = displayNames("resources.json")


  /** Fetch the display name of science packs and other items used by research. */
  suspend fun itemNames(): Map<String, String> = displayNames("items.json")


  /** Fetch the research state of the players' force, or `null` if the mod hasn't sent it yet. */
  suspend fun research(serverId: FactorioServerId): ResearchUpdate? {
    val response = window.fetch("${window.location.origin}/kafkatorio/data/servers/${serverId.id}/research").await()
    if (!response.ok) return null
    val json = response.text().await()
    return jsonMapper.decodeFromString(ResearchUpdate.serializer(), json)
  }


  /** Fetch the technology tree (exported like [buildingInfo]). */
  suspend fun technologies(): Map<String, TechnologyInfo> {
    val response = window.fetch("${window.location.origin}/kafkatorio/icons/technologies.json").await()
    if (!response.ok) return emptyMap()
    val json = response.text().await()
    return lenientJson.decodeFromString(MapSerializer(String.serializer(), TechnologyInfo.serializer()), json)
  }


  /** For data exported from Factorio, which might have more properties in future */
  private val lenientJson = Json { ignoreUnknownKeys = true }


  private suspend fun displayNames(filename: String): Map<String, String> {
    val response = window.fetch("${window.location.origin}/kafkatorio/icons/$filename").await()
    if (!response.ok) return emptyMap()
    val json = response.text().await()
    return jsonMapper.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json)
  }
}


/** The size (in tiles) and display name of a building */
@Serializable
data class BuildingInfo(
  val w: Int,
  val h: Int,
  val name: String,
)


/** A technology, from Factorio's prototype data */
@Serializable
data class TechnologyInfo(
  val name: String,
  val prerequisites: List<String> = emptyList(),
  val order: String = "",
  /** The recipes that this technology unlocks */
  val unlocks: List<String> = emptyList(),
  /** The science packs needed, or `null` if this is researched by a [trigger] */
  val cost: TechnologyCost? = null,
  /** e.g. `{"type": "craft-item", "item": "iron-plate", "count": 10}` */
  val trigger: JsonObject? = null,
  /** A number, or `"infinite"`, for technologies that have levels */
  val maxLevel: JsonPrimitive? = null,
  /** The level of the first technology of a series (e.g. 3 for `mining-productivity-3`) */
  val level: Int? = null,
  val upgrade: Boolean = false,
  val disabled: Boolean = false,
)


@Serializable
data class TechnologyCost(
  /** The number of units, or `null` if [countFormula] is used instead */
  val count: Double? = null,
  /** The number of units for each level, e.g. `2^(L-6)*1000` */
  val countFormula: String? = null,
  /** Seconds per unit */
  val time: Double? = null,
  /** `[science pack, amount per unit]` */
  val ingredients: List<List<JsonPrimitive>> = emptyList(),
  val ignoreMultiplier: Boolean = false,
)
