package dev.adamko.kafkatorio.server.web.production

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.topicName
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.packets.ProductionUpdate
import dev.adamko.kafkatorio.server.web.kafka.followTopic
import kotlinx.serialization.Serializable


/**
 * Keeps how much of each item and fluid was produced and consumed over time, for the production
 * dashboard.
 *
 * The amounts are kept per game minute for the last day, and per game hour for the last week.
 * (The production topic keeps a week of data, which is read again on startup.)
 */
class ProductionService(
  private val appProps: ApplicationProperties,
) {

  private val servers = mutableMapOf<FactorioServerId, ServerProduction>()


  suspend fun consume(): Unit = followTopic(
    appProps,
    ProductionUpdate::class.topicName,
    clientId = "kafkatorio.events-server-web.production",
  ) { serverId, data ->
    if (data is ProductionUpdate && data.forceName == PLAYER_FORCE) {
      synchronized(servers) {
        servers.getOrPut(serverId) { ServerProduction() }.add(data)
      }
    }
  }


  /**
   * The amounts produced and consumed during the last [range], in [ProductionRange.buckets]
   * buckets. If [surfaceIndex] is `null`, sum all surfaces.
   */
  fun production(
    serverId: FactorioServerId,
    surfaceIndex: Int?,
    range: ProductionRange,
  ): ProductionReport = synchronized(servers) {
    servers[serverId]?.report(surfaceIndex, range)
      ?: ProductionReport(range = range.name, bucketMinutes = range.bucketMinutes, latestTick = null)
  }


  /** The surfaces that have production statistics */
  fun surfaces(serverId: FactorioServerId): Set<Int> = synchronized(servers) {
    servers[serverId]?.surfaces?.keys?.toSet() ?: emptySet()
  }


  companion object {
    const val PLAYER_FORCE = "player"
  }
}


enum class ProductionRange(
  val minutes: Int,
  /** The size of each bucket */
  val bucketMinutes: Int,
) {
  TEN_MINUTES(10, 1),
  ONE_HOUR(60, 1),
  SIX_HOURS(6 * 60, 5),
  ONE_DAY(24 * 60, 20),
  ONE_WEEK(7 * 24 * 60, 2 * 60),
  ;

  val buckets: Int get() = minutes / bucketMinutes
}


@Serializable
data class ProductionReport(
  val range: String,
  val bucketMinutes: Int,
  /** The game tick of the latest statistics, or `null` if there are none */
  val latestTick: Long?,
  /** Sorted by the amount produced, largest first */
  val entries: List<ProductionEntry> = emptyList(),
)


@Serializable
data class ProductionEntry(
  /** `item` or `fluid` */
  val kind: String,
  val name: String,
  val produced: Double,
  val consumed: Double,
  /** The amount produced in each bucket, oldest first */
  val producedSeries: List<Double>,
  val consumedSeries: List<Double>,
)


private const val TICKS_PER_MINUTE = 60 * 60
private const val MINUTE_SLOTS = 24 * 60
private const val HOUR_SLOTS = 7 * 24


private class ServerProduction {
  val surfaces = mutableMapOf<Int, SurfaceProduction>()
  var latestMinute = 0L

  fun add(update: ProductionUpdate) {
    val minute = update.tick.value.toLong() / TICKS_PER_MINUTE
    latestMinute = maxOf(latestMinute, minute)
    surfaces.getOrPut(update.surfaceIndex.toString().toInt()) { SurfaceProduction() }.add(minute, update)
  }

  fun report(surfaceIndex: Int?, range: ProductionRange): ProductionReport {
    val selected = if (surfaceIndex == null) surfaces.values else listOfNotNull(surfaces[surfaceIndex])

    val produced = mutableMapOf<Key, DoubleArray>()
    val consumed = mutableMapOf<Key, DoubleArray>()
    selected.forEach { surface ->
      surface.addTo(produced, consumed, range, latestMinute)
    }

    val entries = (produced.keys + consumed.keys).map { key ->
      val producedSeries = produced[key] ?: DoubleArray(range.buckets)
      val consumedSeries = consumed[key] ?: DoubleArray(range.buckets)
      ProductionEntry(
        kind = key.kind,
        name = key.name,
        produced = producedSeries.sum(),
        consumed = consumedSeries.sum(),
        producedSeries = producedSeries.toList(),
        consumedSeries = consumedSeries.toList(),
      )
    }.filter { it.produced > 0 || it.consumed > 0 }
      .sortedWith(compareByDescending<ProductionEntry> { it.produced }.thenByDescending { it.consumed })

    return ProductionReport(
      range = range.name,
      bucketMinutes = range.bucketMinutes,
      latestTick = if (surfaces.isEmpty()) null else latestMinute * TICKS_PER_MINUTE,
      entries = entries,
    )
  }
}


private data class Key(val kind: String, val name: String)


/**
 * Ring buffers of the amounts per minute and per hour, for each item and fluid on a surface.
 *
 * Slots are reused as time goes on: each slot remembers which minute (or hour) it holds, and is
 * cleared when it's reused for a later one.
 */
private class SurfaceProduction {
  private val produced = mutableMapOf<Key, Amounts>()
  private val consumed = mutableMapOf<Key, Amounts>()

  private val slotMinute = LongArray(MINUTE_SLOTS) { -1 }
  private val slotHour = LongArray(HOUR_SLOTS) { -1 }

  fun add(minute: Long, update: ProductionUpdate) {
    val hour = minute / 60
    val minuteSlot = (minute % MINUTE_SLOTS).toInt()
    val hourSlot = (hour % HOUR_SLOTS).toInt()

    if (slotMinute[minuteSlot] != minute) {
      if (slotMinute[minuteSlot] > minute) return // older than a day
      slotMinute[minuteSlot] = minute
      (produced.values + consumed.values).forEach { it.minutes[minuteSlot] = 0f }
    }
    if (slotHour[hourSlot] != hour) {
      slotHour[hourSlot] = hour
      (produced.values + consumed.values).forEach { it.hours[hourSlot] = 0f }
    }

    fun record(target: MutableMap<Key, Amounts>, kind: String, amounts: Map<String, Double>) {
      amounts.forEach { (name, amount) ->
        val slots = target.getOrPut(Key(kind, name)) { Amounts() }
        slots.minutes[minuteSlot] += amount.toFloat()
        slots.hours[hourSlot] += amount.toFloat()
      }
    }
    record(produced, "item", update.items.produced)
    record(consumed, "item", update.items.consumed)
    record(produced, "fluid", update.fluids.produced)
    record(consumed, "fluid", update.fluids.consumed)
  }


  /** Add the amounts in [range] into buckets, by key */
  fun addTo(
    producedTotals: MutableMap<Key, DoubleArray>,
    consumedTotals: MutableMap<Key, DoubleArray>,
    range: ProductionRange,
    latestMinute: Long,
  ) {
    val firstMinute = latestMinute - range.minutes + 1

    fun sumInto(source: Map<Key, Amounts>, target: MutableMap<Key, DoubleArray>) {
      source.forEach { (key, amounts) ->
        val buckets = target.getOrPut(key) { DoubleArray(range.buckets) }
        if (range.minutes <= MINUTE_SLOTS) {
          for (slot in 0 until MINUTE_SLOTS) {
            val minute = slotMinute[slot]
            if (minute < firstMinute || minute > latestMinute) continue
            buckets[((minute - firstMinute) / range.bucketMinutes).toInt()] += amounts.minutes[slot].toDouble()
          }
        } else {
          val firstHour = firstMinute / 60
          for (slot in 0 until HOUR_SLOTS) {
            val hour = slotHour[slot]
            if (hour < firstHour || hour > latestMinute / 60) continue
            val bucket = ((hour * 60 - firstMinute).coerceAtLeast(0) / range.bucketMinutes).toInt()
            buckets[bucket.coerceAtMost(range.buckets - 1)] += amounts.hours[slot].toDouble()
          }
        }
      }
    }
    sumInto(produced, producedTotals)
    sumInto(consumed, consumedTotals)
  }
}


private class Amounts {
  val minutes = FloatArray(MINUTE_SLOTS)
  val hours = FloatArray(HOUR_SLOTS)
}
