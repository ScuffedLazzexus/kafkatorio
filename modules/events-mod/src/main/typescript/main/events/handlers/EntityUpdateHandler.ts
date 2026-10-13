import {
  EventName,
  FactorioEntityUpdateElementStandard,
  KafkatorioPacketData,
  MapChunkPosition,
  PrototypeId,
  Tick
} from "../../../generated/kafkatorio-schema";
import {Converters} from "../converters";
import EventDataCache from "../../emitting/EventDataCache";
import {
  BoundingBox,
  LuaEntity,
  LuaSurface,
  MapPosition,
  OldTileAndPosition,
  OnBuiltEntityEvent,
  OnChunkGeneratedEvent,
  OnEntityClonedEvent,
  OnEntityDamagedEvent,
  OnEntityDiedEvent,
  OnEntityLogisticSlotChangedEvent,
  OnEntityRenamedEvent,
  OnEntitySettingsPastedEvent,
  OnEntitySpawnedEvent,
  OnPlayerMinedEntityEvent,
  OnPlayerMinedTileEvent,
  OnPlayerRepairedEntityEvent,
  OnPlayerRotatedEntityEvent,
  OnPostEntityDiedEvent,
  OnPreEntitySettingsPastedEvent,
  OnRobotBuiltEntityEvent,
  OnRobotMinedEntityEvent,
  OnRobotMinedTileEvent,
  OnSelectedEntityChangedEvent,
  OnTriggerCreatedEntityEvent,
  SurfaceIndex,
} from "factorio:runtime"


export class EntityUpdatesHandler {


  handleMinedTileEvent(event: OnPlayerMinedTileEvent | OnRobotMinedTileEvent) {

    const surface = game.get_surface(event.surface_index)
    if (surface == null) {
      return
    }

    const tilesByChunk: LuaTable<MapChunkPosition, OldTileAndPosition[]> = new LuaTable<MapChunkPosition, OldTileAndPosition[]>()

    for (const tile of event.tiles) {
      const chunkPosition = Converters.tilePositionToChunkPosition(tile.position)
      if (!tilesByChunk.has(chunkPosition)) {
        tilesByChunk.set(chunkPosition, [])
      }
      const tiles = tilesByChunk.get(chunkPosition)
      tiles[tiles.length] = tile
      tilesByChunk.set(chunkPosition, tiles)
    }

    for (const [chunkPos, tiles] of pairs(tilesByChunk)) {

      const entities: LuaEntity[] = []

      for (const tile of tiles) {
        const tileEntities = EntityUpdatesHandler.getTileResourceEntities(surface, tile.position)
        for (const resource of tileEntities) {
          entities[entities.length] = resource
        }
      }

      EntityUpdatesHandler.throttleResourcesUpdate(
          Converters.eventNameString(event.name),
          event.tick as Tick,
          event.surface_index,
          chunkPos,
          entities,
      )
    }
  }


  handleMinedEntityEvent(
      event: OnPlayerMinedEntityEvent | OnRobotMinedEntityEvent,
  ) {
    EntityUpdatesHandler.throttleEntityUpdate(event, event.entity)
  }


  handleBuiltEntityEvent(
      event: OnBuiltEntityEvent | OnRobotBuiltEntityEvent,
  ) {
    EntityUpdatesHandler.throttleEntityUpdate(event, event.entity)
  }


  handleChunkGeneratedEvent(event: OnChunkGeneratedEvent) {
    const entities = EntityUpdatesHandler.getAreaResourceEntities(event.surface, event.area)
    EntityUpdatesHandler.throttleResourcesUpdate(
        Converters.eventNameString(event.name),
        event.tick as Tick,
        event.surface.index,
        Converters.chunkPosition(event.position),
        entities,
    )

    // Buildings that already exist are otherwise only sent when they're built or mined, so
    // include them, so that re-sending chunks (`/kafkatorio chunks`) also sends existing bases.
    // (Newly generated chunks won't contain any.)
    for (const building of EntityUpdatesHandler.getAreaPlayerBuildings(event.surface, event.area)) {
      EntityUpdatesHandler.throttleEntityUpdate(event, building)
    }
  }


  private static throttleEntityUpdate(event: EntityUpdateEvent, entity: LuaEntity) {

    if (entity.unit_number == undefined) {
      // ignore for now - I'm not sure how to track entities without a unit_number
      return
    }
    const eventName = Converters.eventNameString(event.name)

    const entityUpdateKey: KafkatorioPacketData.MapChunkEntityUpdate["key"] = {
      protoId: Converters.prototypeIdEntity(entity),
      surfaceIndex: entity.surface.index,
      chunkPosition: Converters.mapPositionToChunkPosition(entity.position)
    }

    const entityUpdate: FactorioEntityUpdateElementStandard = {
      unitNumber: entity.unit_number,
      graphicsVariation: entity.graphics_variation,
      health: entity.health,
      isActive: entity.active,
      isRotatable: entity.rotatable,
      lastUser: entity.last_user?.index,
      status: Converters.entityStatus(entity.status),
    }

    EventDataCache.throttle<KafkatorioPacketData.MapChunkEntityUpdate>(
        entityUpdateKey,
        KafkatorioPacketData.Type.MapChunkEntityUpdate,
        data => {

          data.events ??= {}
          data.events[eventName] ??= []
          data.events[eventName].push(event.tick)

          data.entitiesXY ??= {}
          data.entitiesXY[`${entity.position.x}`] ??= {}
          data.entitiesXY[`${entity.position.x}`][`${entity.position.y}`] ??= entityUpdate
        }
    )
  }


  private static throttleResourcesUpdate(
      eventName: EventName,
      eventTick: Tick,
      surfaceIndex: SurfaceIndex,
      chunkPosition: MapChunkPosition,
      entities: LuaEntity[],
  ) {

    // first group by prototype ID
    const entitiesByProtoId: { [protoId: PrototypeId]: LuaEntity[] } = {}

    for (const entity of entities) {
      const protoId = Converters.prototypeIdEntity(entity)
      entitiesByProtoId[protoId] ??= []
      entitiesByProtoId[protoId].push(entity)
    }

    for (const [protoId, resourceEntities] of pairs(entitiesByProtoId)) {

      // create a MapChunkResourceUpdate for each prototype ID

      const entityUpdateKey: KafkatorioPacketData.MapChunkResourceUpdate["key"] = {
        protoId: protoId,
        surfaceIndex: surfaceIndex,
        chunkPosition: chunkPosition,
      }

      EventDataCache.throttle<KafkatorioPacketData.MapChunkResourceUpdate>(
          entityUpdateKey,
          KafkatorioPacketData.Type.MapChunkResourceUpdate,
          data => {

            data.events ??= {}
            data.events[eventName] ??= []
            data.events[eventName].push(eventTick)

            for (const entity of resourceEntities) {

              data.amounts ??= {}
              data.amounts[`${entity.position.x}`] ??= {}
              data.amounts[`${entity.position.x}`][`${entity.position.y}`] = entity.amount

              // initial_amount can be nil even for infinite resources (e.g. crude oil), so also
              // check the prototype. An initial amount marks the resource as infinite.
              if (entity.prototype.infinite_resource) {
                data.initialAmounts ??= {}
                data.initialAmounts[`${entity.position.x}`] ??= {}
                data.initialAmounts[`${entity.position.x}`][`${entity.position.y}`] =
                    entity.initial_amount ?? entity.prototype.normal_resource_amount ?? 0
              }
            }
          }
      )
    }
  }


  /** Get all resources on a tile-position */
  private static getTileResourceEntities(
      surface: LuaSurface,
      mapPosition: MapPosition,
  ): LuaEntity[] {
    return surface.find_entities_filtered({
          position: mapPosition,
          type: "resource",
        }
    )
  }


  /** Get all player-force buildings within a map-area (excluding player characters) */
  private static getAreaPlayerBuildings(
      surface: LuaSurface,
      area: BoundingBox,
  ): LuaEntity[] {
    const buildings: LuaEntity[] = []
    for (const entity of surface.find_entities_filtered({area: area, force: "player"})) {
      if (entity.type != "character") {
        buildings[buildings.length] = entity
      }
    }
    return buildings
  }


  /** Get all resources within a map-area */
  private static getAreaResourceEntities(
      surface: LuaSurface,
      area: BoundingBox,
  ): LuaEntity[] {
    return surface.find_entities_filtered({
          area: area,
          type: "resource",
        }
    )
  }
}

const EntityUpdates = new EntityUpdatesHandler()

export default EntityUpdates


type EntityUpdateEvent =
    | OnBuiltEntityEvent
    | OnChunkGeneratedEvent

    | OnEntityClonedEvent
    | OnEntityDamagedEvent
    | OnEntityDiedEvent
    | OnEntityLogisticSlotChangedEvent
    | OnEntityRenamedEvent
    | OnEntitySettingsPastedEvent
    | OnEntitySpawnedEvent

    | OnPlayerMinedEntityEvent
    | OnPlayerRepairedEntityEvent
    | OnPlayerRotatedEntityEvent

    | OnRobotMinedEntityEvent
    | OnRobotBuiltEntityEvent

    | OnPostEntityDiedEvent
    | OnPreEntitySettingsPastedEvent

    | OnSelectedEntityChangedEvent
    | OnTriggerCreatedEntityEvent
