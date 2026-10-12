import {DefinedEventName} from "../types";
import {
  ChunkSize,
  Colour,
  EntityMiningProperties,
  EventName,
  FactorioEntityStatus,
  KafkatorioPacketData,
  MapBoundingBox,
  MapChunkPosition,
  MapEntityPosition,
  MinedProduct,
  PrototypeId,
} from "../../generated/kafkatorio-schema";


export namespace Converters {


  import floor = math.floor;
  const mapEventIdToName = new LuaTable<defines.events, DefinedEventName>()
  for (const [eventName, eventId] of pairs(defines.events)) {
    mapEventIdToName.set(eventId, eventName)
  }


  export function eventNameString(event: defines.events): EventName {
    return mapEventIdToName.get(event) as EventName
  }


  export function mapColour(color: Color): Colour {
    return [
      color.r ?? 0,
      color.g ?? 0,
      color.b ?? 0,
      color.a ?? 0,
    ]
  }


  export function convertPlacedTiles(
      placedTile: LuaTilePrototype,
      oldTiles: OldTileAndPosition[],
  ): Tile[] {
    const converted: Tile[] = []
    for (const [, tile] of ipairs(oldTiles)) {
      converted[converted.length] = {
        position: {x: tile.position.x, y: tile.position.y},
        name: placedTile.name,
      }
    }
    return converted
  }


  export function convertRemovedTiles(
      surface: LuaSurface,
      oldTiles: OldTileAndPosition[],
  ): Tile[] {
    const converted: Tile[] = []
    for (const [, tile] of ipairs(oldTiles)) {
      converted[converted.length] = surface.get_tile(tile.position.x, tile.position.y)
    }
    return converted
  }


  export function convertCollisionMaskToNames(cm: CollisionMask): string[] {
    let masks: string[] = []
    for (const [name] of pairs(cm.layers)) {
      masks.push(name)
    }
    return masks
  }


  function convertEntityStatus(
      status: defines.entity_status | undefined
  ): FactorioEntityStatus | null {
    if (status == undefined) {
      return null
    } else {
      return EntityStatusRecord[status]
    }
  }


  export function prototypeIdEntity(entity: LuaEntity): PrototypeId {
    return prototypeId(entity.type, entity.name)
  }


  export function prototypeId(type: string, name: string): PrototypeId {
    return `${type}/${name}` as PrototypeId
  }


  export function tilePositionToChunkPosition(mapPosition: TilePosition): MapChunkPosition {
    return [floor(mapPosition.x / 32), floor(mapPosition.y / 32), ChunkSize.CHUNK_032]
  }


  export function mapPositionToChunkPosition(mapPosition: MapPosition): MapChunkPosition {
    return [floor(mapPosition.x / 32), floor(mapPosition.y / 32), ChunkSize.CHUNK_032]
  }


  export function entityPosition(position: MapPosition): MapEntityPosition {
    return [position.x, position.y];
  }


  export function chunkPosition(position: ChunkPosition): MapChunkPosition {
    return [position.x, position.y, ChunkSize.CHUNK_032]
  }


  export function entityStatus(status: defines.entity_status | undefined): FactorioEntityStatus | undefined {
    if (status == undefined) {
      return undefined
    } else {
      return EntityStatusRecord[status]
    }
  }


  export function miningProperties(
      properties: LuaEntityPrototype["mineable_properties"]
  ): EntityMiningProperties {

    const products: MinedProduct[] = []

    if (properties.products != null) {
      for (const product of properties.products) {
        if (product.type == "fluid") {
          products[products.length] = {
            type: MinedProduct.Type.MinedProductFluid,
            amount: product.amount,
            resultProtoId: Converters.prototypeId(product.type, product.name),
          }
        }
        if (product.type == "item") {
          products[products.length] = {
            type: MinedProduct.Type.MinedProductItem,
            amount: product.amount,
            resultProtoId: Converters.prototypeId(product.type, product.name),
          }
        }
      }
    }

    return {
      canBeMined: properties.minable,
      products: products,
    }
  }


  export function playerOnlineInfo(player: LuaPlayer, data: KafkatorioPacketData.PlayerUpdate) {
    data.lastOnline = player.last_online
    data.onlineTime = player.online_time
    data.afkTime = player.afk_time
    data.isConnected = player.connected
  }


  export function collisionBox(collision_box: BoundingBox): MapBoundingBox {
    return {
      leftTop: Converters.entityPosition(collision_box.left_top),
      rightBottom: Converters.entityPosition(collision_box.right_bottom),
    }
  }


  const EntityStatusRecord: Record<defines.entity_status, FactorioEntityStatus> = {
    //@formatter:off
    [ defines.entity_status.cant_divide_segments             ]: FactorioEntityStatus.CANT_DIVIDE_SEGMENTS,
    [ defines.entity_status.charging                         ]: FactorioEntityStatus.CHARGING,
    [ defines.entity_status.closed_by_circuit_network        ]: FactorioEntityStatus.CLOSED_BY_CIRCUIT_NETWORK,
    [ defines.entity_status.disabled                         ]: FactorioEntityStatus.DISABLED,
    [ defines.entity_status.disabled_by_control_behavior     ]: FactorioEntityStatus.DISABLED_BY_CONTROL_BEHAVIOR,
    [ defines.entity_status.disabled_by_script               ]: FactorioEntityStatus.DISABLED_BY_SCRIPT,
    [ defines.entity_status.discharging                      ]: FactorioEntityStatus.DISCHARGING,
    [ defines.entity_status.fluid_ingredient_shortage        ]: FactorioEntityStatus.FLUID_INGREDIENT_SHORTAGE,
    [ defines.entity_status.fully_charged                    ]: FactorioEntityStatus.FULLY_CHARGED,
    [ defines.entity_status.full_output                      ]: FactorioEntityStatus.FULL_OUTPUT,
    [ defines.entity_status.item_ingredient_shortage         ]: FactorioEntityStatus.ITEM_INGREDIENT_SHORTAGE,
    [ defines.entity_status.launching_rocket                 ]: FactorioEntityStatus.LAUNCHING_ROCKET,
    [ defines.entity_status.low_input_fluid                  ]: FactorioEntityStatus.LOW_INPUT_FLUID,
    [ defines.entity_status.low_power                        ]: FactorioEntityStatus.LOW_POWER,
    [ defines.entity_status.low_temperature                  ]: FactorioEntityStatus.LOW_TEMPERATURE,
    [ defines.entity_status.marked_for_deconstruction        ]: FactorioEntityStatus.MARKED_FOR_DECONSTRUCTION,
    [ defines.entity_status.missing_required_fluid           ]: FactorioEntityStatus.MISSING_REQUIRED_FLUID,
    [ defines.entity_status.missing_science_packs            ]: FactorioEntityStatus.MISSING_SCIENCE_PACKS,
    [ defines.entity_status.networks_connected               ]: FactorioEntityStatus.NETWORKS_CONNECTED,
    [ defines.entity_status.networks_disconnected            ]: FactorioEntityStatus.NETWORKS_DISCONNECTED,
    [ defines.entity_status.normal                           ]: FactorioEntityStatus.NORMAL,
    [ defines.entity_status.not_connected_to_rail            ]: FactorioEntityStatus.NOT_CONNECTED_TO_RAIL,
    [ defines.entity_status.not_plugged_in_electric_network  ]: FactorioEntityStatus.NOT_PLUGGED_IN_ELECTRIC_NETWORK,
    [ defines.entity_status.no_ammo                          ]: FactorioEntityStatus.NO_AMMO,
    [ defines.entity_status.no_fuel                          ]: FactorioEntityStatus.NO_FUEL,
    [ defines.entity_status.no_ingredients                   ]: FactorioEntityStatus.NO_INGREDIENTS,
    [ defines.entity_status.no_input_fluid                   ]: FactorioEntityStatus.NO_INPUT_FLUID,
    [ defines.entity_status.no_minable_resources             ]: FactorioEntityStatus.NO_MINABLE_RESOURCES,
    [ defines.entity_status.no_modules_to_transmit           ]: FactorioEntityStatus.NO_MODULES_TO_TRANSMIT,
    [ defines.entity_status.no_power                         ]: FactorioEntityStatus.NO_POWER,
    [ defines.entity_status.no_recipe                        ]: FactorioEntityStatus.NO_RECIPE,
    [ defines.entity_status.no_research_in_progress          ]: FactorioEntityStatus.NO_RESEARCH_IN_PROGRESS,
    [ defines.entity_status.opened_by_circuit_network        ]: FactorioEntityStatus.OPENED_BY_CIRCUIT_NETWORK,
    [ defines.entity_status.out_of_logistic_network          ]: FactorioEntityStatus.OUT_OF_LOGISTIC_NETWORK,
    [ defines.entity_status.preparing_rocket_for_launch      ]: FactorioEntityStatus.PREPARING_ROCKET_FOR_LAUNCH,
    [ defines.entity_status.recharging_after_power_outage    ]: FactorioEntityStatus.RECHARGING_AFTER_POWER_OUTAGE,
    [ defines.entity_status.turned_off_during_daytime        ]: FactorioEntityStatus.TURNED_OFF_DURING_DAYTIME,
    [ defines.entity_status.waiting_for_source_items         ]: FactorioEntityStatus.WAITING_FOR_SOURCE_ITEMS,
    [ defines.entity_status.waiting_for_space_in_destination ]: FactorioEntityStatus.WAITING_FOR_SPACE_IN_DESTINATION,
    [ defines.entity_status.waiting_for_target_to_be_built   ]: FactorioEntityStatus.WAITING_FOR_TARGET_TO_BE_BUILT,
    [ defines.entity_status.waiting_for_train                ]: FactorioEntityStatus.WAITING_FOR_TRAIN,
    [ defines.entity_status.waiting_to_launch_rocket         ]: FactorioEntityStatus.WAITING_TO_LAUNCH_ROCKET,
    [ defines.entity_status.working                          ]: FactorioEntityStatus.WORKING,

    // Factorio 2.0 additions (Space Age / space platforms, agricultural towers, etc.)
    [ defines.entity_status.ghost                             ]: FactorioEntityStatus.GHOST,
    [ defines.entity_status.broken                            ]: FactorioEntityStatus.BROKEN,
    [ defines.entity_status.frozen                            ]: FactorioEntityStatus.FROZEN,
    [ defines.entity_status.paused                            ]: FactorioEntityStatus.PAUSED,
    [ defines.entity_status.not_connected_to_hub_or_pad       ]: FactorioEntityStatus.NOT_CONNECTED_TO_HUB_OR_PAD,
    [ defines.entity_status.not_enough_space_in_output        ]: FactorioEntityStatus.NOT_ENOUGH_SPACE_IN_OUTPUT,
    [ defines.entity_status.full_burnt_result_output          ]: FactorioEntityStatus.FULL_BURNT_RESULT_OUTPUT,
    [ defines.entity_status.waiting_for_more_items            ]: FactorioEntityStatus.WAITING_FOR_MORE_ITEMS,
    [ defines.entity_status.waiting_for_space_in_platform_hub ]: FactorioEntityStatus.WAITING_FOR_SPACE_IN_PLATFORM_HUB,
    [ defines.entity_status.thrust_not_required               ]: FactorioEntityStatus.THRUST_NOT_REQUIRED,
    [ defines.entity_status.on_the_way                        ]: FactorioEntityStatus.ON_THE_WAY,
    [ defines.entity_status.waiting_in_orbit                  ]: FactorioEntityStatus.WAITING_IN_ORBIT,
    [ defines.entity_status.waiting_at_stop                   ]: FactorioEntityStatus.WAITING_AT_STOP,
    [ defines.entity_status.waiting_for_rockets_to_arrive     ]: FactorioEntityStatus.WAITING_FOR_ROCKETS_TO_ARRIVE,
    [ defines.entity_status.not_enough_thrust                 ]: FactorioEntityStatus.NOT_ENOUGH_THRUST,
    [ defines.entity_status.destination_stop_full             ]: FactorioEntityStatus.DESTINATION_STOP_FULL,
    [ defines.entity_status.no_path                           ]: FactorioEntityStatus.NO_PATH,
    [ defines.entity_status.no_filter                         ]: FactorioEntityStatus.NO_FILTER,
    [ defines.entity_status.no_spot_seedable_by_inputs        ]: FactorioEntityStatus.NO_SPOT_SEEDABLE_BY_INPUTS,
    [ defines.entity_status.waiting_for_plants_to_grow        ]: FactorioEntityStatus.WAITING_FOR_PLANTS_TO_GROW,
    [ defines.entity_status.computing_navigation              ]: FactorioEntityStatus.COMPUTING_NAVIGATION,
    [ defines.entity_status.pipeline_overextended             ]: FactorioEntityStatus.PIPELINE_OVEREXTENDED,
    [ defines.entity_status.recipe_not_researched             ]: FactorioEntityStatus.RECIPE_NOT_RESEARCHED,
    [ defines.entity_status.recipe_is_parameter               ]: FactorioEntityStatus.RECIPE_IS_PARAMETER,
    //@formatter:on
  }
}
