package dev.adamko.kafkatorio.webmap.state

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.packets.EventServerPacket
import dev.adamko.kafkatorio.webmap.research.ResearchLayout
import dev.adamko.kafkatorio.webmap.rootJob
import dev.adamko.kafkatorio.webmap.routing.SiteView
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


data class SiteState(
  val view: SiteView = SiteView.HOME,

  val serverIds: List<FactorioServerId>? = null,

  val factorioGameState: FactorioGameState? = null,

  /** The server whose pages are shown (the map, or research) */
  val serverId: FactorioServerId? = null,
) : CoroutineScope {

  override val coroutineContext: CoroutineContext =
    Dispatchers.Default + SupervisorJob(rootJob) + CoroutineName("SiteState")

  operator fun plus(action: SiteAction): SiteState = when (action) {
    is SiteAction.HomePage          -> copy(view = SiteView.HOME, serverId = null)

    is SiteAction.ServerPage        -> copy(
      view = SiteView.SERVER,
      serverId = action.serverId,
      // keep the map when coming back from another page of the same server
      factorioGameState = factorioGameState?.takeIf { it.serverId == action.serverId }
        ?: FactorioGameState(action.serverId),
    )

    is SiteAction.ResearchPage      -> copy(
      view = if (action.layout == ResearchLayout.TREE) SiteView.RESEARCH_TREE else SiteView.RESEARCH,
      serverId = action.serverId,
    )

    is SiteAction.ProductionPage    -> copy(view = SiteView.PRODUCTION, serverId = action.serverId)

    is SiteAction.ServerIdsLoaded   -> copy(serverIds = action.serverIds)

    is SiteAction.FactorioUpdate    -> copy(
      factorioGameState = factorioGameState?.let { it + action }
    )

    is SiteAction.EventServerUpdate -> when (action.packet) {
      is EventServerPacket.ChunkTileSaved -> {
        if (factorioGameState?.map != null) {
          launch {
            factorioGameState.map.refreshUpdatedTilePng(action.packet.filename)
          }
        }
        this
      }

      is EventServerPacket.Kafkatorio     -> {
        this
      }
    }
  }
}
