package dev.adamko.kafkatorio.schema.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * A group of nearby resource tiles of the same type (an ore field, or an oil field), as shown on
 * the in-game map.
 */
@Serializable
@SerialName("kafkatorio.resource.ResourcePatch")
data class ResourcePatch(
  val protoId: PrototypeId,
  val surfaceIndex: SurfaceIndex,
  /**
   * The total remaining amount. For infinite resources (e.g. crude oil) this is the sum of the
   * current yields, so it's only meaningful relative to [tileCount].
   */
  val totalAmount: Long,
  /** The number of resource entities (ore tiles, or oil wells) in the patch. */
  val tileCount: Int,
  /** Infinite resources (e.g. crude oil) never deplete, and are counted by wells, not amount. */
  val isInfinite: Boolean,
  val centre: MapEntityPosition,
  val leftTop: MapEntityPosition,
  val rightBottom: MapEntityPosition,
)
