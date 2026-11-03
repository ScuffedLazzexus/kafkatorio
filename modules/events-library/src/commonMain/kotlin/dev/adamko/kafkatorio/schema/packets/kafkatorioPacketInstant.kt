package dev.adamko.kafkatorio.schema.packets

import dev.adamko.kafkatorio.library.LuaJsonList
import dev.adamko.kafkatorio.schema.common.FactorioPrototype
import dev.adamko.kafkatorio.schema.common.PlayerIndex
import dev.adamko.kafkatorio.schema.common.SurfaceIndex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** No key, no caching. Must be sent instantly. */
@Serializable
@SerialName("kafkatorio.packet.KafkatorioPacket")
sealed class KafkatorioInstantPacketData : KafkatorioPacketData()


/*  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  *** */


@Serializable
@SerialName("kafkatorio.packet.instant.ConfigurationUpdate")
data class ConfigurationUpdate(
  val factorioData: ConfigurationUpdateGameData,
//  @Contextual
  val allMods: LuaJsonList<ConfigurationUpdateModData>,
  val modStartupSettingsChange: Boolean = false,
  val migrationApplied: Boolean = false,
) : KafkatorioInstantPacketData() {

  @Serializable
  @SerialName("kafkatorio.packet.instant.ConfigurationUpdate.ConfigurationUpdateGameData")
  data class ConfigurationUpdateGameData(
    val oldVersion: String? = null,
    val newVersion: String? = null,
  )

  @Serializable
  @SerialName("kafkatorio.packet.instant.ConfigurationUpdate.ConfigurationUpdateModData")
  data class ConfigurationUpdateModData(
    val modName: String,
    val currentVersion: String? = null,
    val previousVersion: String? = null,
  )
}


@Serializable
@SerialName("kafkatorio.packet.instant.ConsoleChatUpdate")
data class ConsoleChatUpdate(
  val authorPlayerIndex: PlayerIndex?,
  val content: String,
) : KafkatorioInstantPacketData()


@Serializable
@SerialName("kafkatorio.packet.instant.ConsoleCommandUpdate")
data class ConsoleCommandUpdate(
  val authorPlayerIndex: PlayerIndex?,
  val command: String,
  val parameters: String,
) : KafkatorioInstantPacketData()


@Serializable
@SerialName("kafkatorio.packet.instant.PrototypesUpdate")
data class PrototypesUpdate(
//  @Contextual
  val prototypes: LuaJsonList<FactorioPrototype>,
) : KafkatorioInstantPacketData()


@Serializable
@SerialName("kafkatorio.packet.instant.SurfaceUpdate")
data class SurfaceUpdate(
  val index: SurfaceIndex,
  val daytime: Double,
  val name: String,
) : KafkatorioInstantPacketData()


/*  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  ***  *** */


/** The research state of a force: what's researched, and what's being researched. */
@Serializable
@SerialName("kafkatorio.packet.instant.ResearchUpdate")
data class ResearchUpdate(
  val forceName: String,
  /** The names of all researched technologies */
  val researched: LuaJsonList<String>,
  /**
   * The level of technologies that can be researched more than once (e.g. infinite mining
   * productivity). The level is the next one to research.
   */
  val levels: Map<String, Int> = emptyMap(),
  /** The technology that's being researched */
  val current: String? = null,
  /** Progress of [current], from 0 to 1 */
  val progress: Double? = null,
  /** The research queue, starting with [current] */
  val queue: LuaJsonList<String> = LuaJsonList(emptyList()),
  /** The map setting that multiplies the cost of all technologies */
  val technologyPriceMultiplier: Double = 1.0,
) : KafkatorioInstantPacketData()
