import PacketEmitter from "../emitting/PacketEmitter";
import {KafkatorioPacketData} from "../../generated/kafkatorio-schema";
import {LuaForce, OnTickEvent} from "factorio:runtime"
import Type = KafkatorioPacketData.Type;


/** Research progress changes constantly, so send it at most this often */
const PROGRESS_INTERVAL_TICKS = 60 * 60

/** Batch research events (e.g. queueing several technologies at once) */
const EVENT_DELAY_TICKS = 60


/** Ticks at which each force's research should be sent, by force name */
const pendingForces: Record<string, number> = {}


function scheduleResearchUpdate(force: LuaForce) {
  const due = game.tick + EVENT_DELAY_TICKS
  const pending = pendingForces[force.name]
  if (pending == undefined || pending > due) {
    pendingForces[force.name] = due
  }
}


/** Send the research state of the 'player' force, and of any forces with research events */
export function handleResearchTick(event: OnTickEvent) {
  if (event.tick % PROGRESS_INTERVAL_TICKS == 0) {
    const player = game.forces["player"]
    if (player != undefined) {
      pendingForces[player.name] = event.tick
    }
  }

  for (const [forceName, due] of pairs(pendingForces)) {
    if (due <= event.tick) {
      delete pendingForces[forceName]
      const force = game.forces[forceName]
      if (force != undefined) {
        emitResearchUpdate(force)
      }
    }
  }
}


function emitResearchUpdate(force: LuaForce) {
  const researched: string[] = []
  const levels: Record<string, number> = {}

  for (const [name, technology] of pairs(force.technologies)) {
    if (technology.researched) {
      researched[researched.length] = name
    }
    if (technology.prototype.max_level > 1) {
      levels[name] = technology.level
    }
  }

  const queue: string[] = []
  for (const technology of force.research_queue ?? []) {
    queue[queue.length] = technology.name
  }

  const current = force.current_research

  const update: KafkatorioPacketData.ResearchUpdate = {
    type: Type.ResearchUpdate,
    forceName: force.name,
    researched: researched,
    levels: levels,
    current: current?.name,
    progress: current != undefined ? force.research_progress : undefined,
    queue: queue,
    technologyPriceMultiplier: game.difficulty_settings.technology_price_multiplier,
  }

  PacketEmitter.emitInstantPacket(update)
}


script.on_event(
    [
      defines.events.on_research_started,
      defines.events.on_research_finished,
      defines.events.on_research_cancelled,
      defines.events.on_research_reversed,
      defines.events.on_research_queued,
      defines.events.on_research_moved,
    ],
    (event) => {
      const e = event as unknown as { research?: { force: LuaForce }, force?: LuaForce }
      const force = e.force ?? e.research?.force
      if (force != undefined) {
        scheduleResearchUpdate(force)
      }
    },
)
