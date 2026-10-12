export class EventDataQueueManager {

  static init(force?: boolean) {

    const isAnythingUndefined = storage.store == undefined ||
                                storage.size == undefined ||
                                storage.head == undefined ||
                                storage.tail == undefined

    log(`Initialising EventDataQueueManager globals (force=${force}, isAnythingUndefined=${isAnythingUndefined})`)

    if (force == true || isAnythingUndefined) {
      storage.store = {}
      storage.head = null
      storage.tail = null
      storage.size = 0
    }

    log(`Finished initialising EventDataQueueManager`)
  }


  size(): uint | undefined {
    return storage.size
  }


  enqueue(key: string, event: EventData, weight: int = 1) {
    if (key ! in storage.store) {
      let node = new Node(key, weight)
      if (storage.head == null) {
        storage.head = node
      }
      if (storage.tail != null) {
        storage.tail.next = node
      }
      storage.tail = node
      storage.size++
    }

    storage.store[key] = event
  }


  dequeue(): WeightedPacket | null {
    if (storage.head == null) {
      return null
    } else {
      let weight = storage.head.weight
      let storeKey = storage.head.storeKey
      storage.head = storage.head.next
      storage.size--

      let storedPacket = storage.store[storeKey]
      storage.store[storeKey] = null

      if (storedPacket == null) {
        return null
      } else {
        return {
          value: storedPacket,
          weight: weight,
        }
      }
    }
  }


  dequeueValues(targetWeight: uint = 50): EventData[] {
    let values: EventData[] = []
    let weight: uint = 0

    do {
      let packet = this.dequeue()

      if (packet == null) {
        break;
      } else {
        weight += packet.weight
        values[values.length] = packet.value
      }
    } while (weight <= targetWeight)

    return values
  }

}


const EventDataQueue = new EventDataQueueManager()


export default EventDataQueue


declare const storage: {
  store: EventQueueStore

  head: Node | null
  tail: Node | null

  size: uint
}


class Node {
  storeKey: string;
  next: Node | null = null;
  weight: uint

  constructor(storeKey: string, weight: uint) {
    this.storeKey = storeKey;
    this.weight = weight
  }
}


interface EventQueueStore {
  [key: string]: EventData | null;
}


interface WeightedPacket {
  value: EventData,
  weight: int,
}
