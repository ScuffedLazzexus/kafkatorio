import PacketEmitter from "../emitting/PacketEmitter";
import {KafkatorioPacketData, ProductionCounts, Tick, UInt} from "../../generated/kafkatorio-schema";
import {LuaFlowStatistics, LuaForce, LuaSurface, OnTickEvent, SurfaceIndex} from "factorio:runtime"
import Type = KafkatorioPacketData.Type;


/** Send production statistics this often */
const PRODUCTION_INTERVAL_TICKS = 60 * 60

/** Spread the surfaces over the interval, rather than sending them all on the same tick */
const PRODUCTION_OFFSET_TICKS = 30


/** Totals of a LuaFlowStatistics, by name. Produced = input, consumed = output. */
interface FlowTotals {
  produced: Record<string, number>
  consumed: Record<string, number>
}

interface ProductionTotals {
  tick: number
  items: FlowTotals
  fluids: FlowTotals
}


/**
 * Send what the 'player' force produced and consumed on each surface since the last update.
 *
 * Factorio's statistics are running totals, so the totals of the last update are kept in
 * `storage`, and only the difference is sent.
 */
export function handleProductionTick(event: OnTickEvent) {
  const offset = event.tick % PRODUCTION_INTERVAL_TICKS
  if (offset % PRODUCTION_OFFSET_TICKS != 0) return

  const force = game.forces["player"]
  if (force == undefined) return

  // e.g. surfaces 1, 3, 5... on offset 0, surfaces 2, 4... on offset 30
  const slots = PRODUCTION_INTERVAL_TICKS / PRODUCTION_OFFSET_TICKS
  const slot = offset / PRODUCTION_OFFSET_TICKS
  for (const [, surface] of game.surfaces) {
    if (surface.index % slots == slot) {
      emitProductionUpdate(force, surface, event.tick)
    }
  }
}


function emitProductionUpdate(force: LuaForce, surface: LuaSurface, tick: number) {
  storage.productionTotals ??= {}
  const key = `${force.name}/${surface.index}`

  const current: ProductionTotals = {
    tick: tick,
    items: flowTotals(force.get_item_production_statistics(surface)),
    fluids: flowTotals(force.get_fluid_production_statistics(surface)),
  }
  const previous = storage.productionTotals[key] as ProductionTotals | undefined
  storage.productionTotals[key] = current

  // the first time, there's nothing to compare with
  if (previous == undefined) return

  const items = difference(previous.items, current.items)
  const fluids = difference(previous.fluids, current.fluids)
  if (isEmpty(items) && isEmpty(fluids)) return

  const update: KafkatorioPacketData.ProductionUpdate = {
    type: Type.ProductionUpdate,
    forceName: force.name,
    surfaceIndex: surface.index as unknown as SurfaceIndex,
    tick: tick as Tick,
    periodTicks: (tick - previous.tick) as UInt,
    items: items,
    fluids: fluids,
  }
  PacketEmitter.emitInstantPacket(update)
}


function flowTotals(statistics: LuaFlowStatistics): FlowTotals {
  const produced: Record<string, number> = {}
  const consumed: Record<string, number> = {}
  for (const [name, count] of pairs(statistics.input_counts)) {
    produced[name] = count as number
  }
  for (const [name, count] of pairs(statistics.output_counts)) {
    consumed[name] = count as number
  }
  return {produced: produced, consumed: consumed}
}


function difference(previous: FlowTotals, current: FlowTotals): ProductionCounts {
  return {
    produced: differenceOf(previous.produced, current.produced),
    consumed: differenceOf(previous.consumed, current.consumed),
  }
}


function differenceOf(
    previous: Record<string, number>,
    current: Record<string, number>,
): Record<string, number> {
  const result: Record<string, number> = {}
  for (const [name, count] of pairs(current)) {
    const change = count - (previous[name] ?? 0)
    // totals can go down if statistics are reset, so ignore negative changes
    if (change > 0) {
      result[name] = change
    }
  }
  return result
}


function isEmpty(counts: ProductionCounts): boolean {
  return isEmptyTable(counts.produced) && isEmptyTable(counts.consumed)
}


function isEmptyTable(table: Record<string, number> | null | undefined): boolean {
  if (table == undefined) return true
  for (const [,] of pairs(table)) {
    return false
  }
  return true
}
