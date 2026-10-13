package dev.adamko.kafkatorio.webmap.services

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.ResourcePatch
import dev.adamko.kafkatorio.webmap.config.jsonMapper
import kotlinx.serialization.builtins.ListSerializer
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


}
