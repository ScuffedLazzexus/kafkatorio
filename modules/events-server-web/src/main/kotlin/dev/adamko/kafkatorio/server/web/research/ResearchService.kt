package dev.adamko.kafkatorio.server.web.research

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.topicName
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.packets.ResearchUpdate
import dev.adamko.kafkatorio.server.web.kafka.followTopic
import java.util.concurrent.ConcurrentHashMap


/** Keeps the latest research state of each force, for the research page. */
class ResearchService(
  private val appProps: ApplicationProperties,
) {

  private val research = ConcurrentHashMap<Pair<FactorioServerId, String>, ResearchUpdate>()


  /** The mod sends the research state when research changes, and regularly with the progress. */
  suspend fun consume(): Unit = followTopic(
    appProps,
    ResearchUpdate::class.topicName,
    clientId = "kafkatorio.events-server-web.research",
  ) { serverId, data ->
    if (data is ResearchUpdate) research[serverId to data.forceName] = data
  }


  fun research(serverId: FactorioServerId, forceName: String = PLAYER_FORCE): ResearchUpdate? =
    research[serverId to forceName]


  companion object {
    /** The force that players are in, by default */
    const val PLAYER_FORCE = "player"
  }
}
