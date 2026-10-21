package dev.adamko.kafkatorio.schema.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** The latest known state of a building (a player-built entity), as shown on the web map. */
@Serializable
@SerialName("kafkatorio.building.MapBuilding")
data class MapBuilding(
  val protoId: PrototypeId,
  /** The centre of the building */
  val position: MapEntityPosition,
  val status: FactorioEntityData.Status? = null,
  val health: Float? = null,
  val unitNumber: UnitNumber? = null,
)
