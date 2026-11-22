package dev.adamko.kafkatorio.webmap.state

import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.common.Tick
import dev.adamko.kafkatorio.schema.packets.EventServerPacket
import dev.adamko.kafkatorio.schema.packets.PlayerUpdate
import dev.adamko.kafkatorio.webmap.research.ResearchLayout
import io.kvision.redux.RAction


sealed interface SiteAction : RAction {
  object HomePage : SiteAction
  data class ServerPage(val serverId: FactorioServerId) : SiteAction
  data class ResearchPage(val serverId: FactorioServerId, val layout: ResearchLayout) : SiteAction
  data class ProductionPage(val serverId: FactorioServerId) : SiteAction


  data class ServerIdsLoaded(val serverIds: List<FactorioServerId>) : SiteAction


  /** Updates from `events-server` */
  sealed class FactorioUpdate : SiteAction {
    data class Player(val tick: Tick, val data: PlayerUpdate) : FactorioUpdate()
  }

  data class EventServerUpdate(val packet: EventServerPacket) : SiteAction
}
