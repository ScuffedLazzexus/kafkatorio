package dev.adamko.kafkatorio.schema.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** A surface (e.g. Nauvis, or a Space Exploration planet or orbit) that has map data. */
@Serializable
@SerialName("kafkatorio.surface.MapSurface")
data class MapSurface(
  val index: SurfaceIndex,
  val name: String,
)
