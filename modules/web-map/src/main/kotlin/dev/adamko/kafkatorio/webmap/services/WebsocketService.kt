package dev.adamko.kafkatorio.webmap.services

import dev.adamko.kafkatorio.schema.packets.ConfigurationUpdate
import dev.adamko.kafkatorio.schema.packets.ConsoleChatUpdate
import dev.adamko.kafkatorio.schema.packets.ConsoleCommandUpdate
import dev.adamko.kafkatorio.schema.packets.EntityUpdate
import dev.adamko.kafkatorio.schema.packets.EventServerPacket
import dev.adamko.kafkatorio.schema.packets.KafkatorioPacketDataError
import dev.adamko.kafkatorio.schema.packets.MapChunkEntityUpdate
import dev.adamko.kafkatorio.schema.packets.MapChunkResourceUpdate
import dev.adamko.kafkatorio.schema.packets.MapChunkTileUpdate
import dev.adamko.kafkatorio.schema.packets.PlayerUpdate
import dev.adamko.kafkatorio.schema.packets.PrototypesUpdate
import dev.adamko.kafkatorio.schema.packets.ProductionUpdate
import dev.adamko.kafkatorio.schema.packets.ResearchUpdate
import dev.adamko.kafkatorio.schema.packets.SurfaceUpdate
import dev.adamko.kafkatorio.webmap.App
import dev.adamko.kafkatorio.webmap.state.SiteAction
import dev.adamko.kafkatorio.webmap.config.ApplicationProperties
import dev.adamko.kafkatorio.webmap.config.jsonMapper
import dev.adamko.kafkatorio.webmap.rootJob
import kotlin.coroutines.CoroutineContext
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event


object WebsocketService : CoroutineScope {

  override val coroutineContext: CoroutineContext =
    Dispatchers.Default + CoroutineName("WebsocketService") + Job(rootJob)

  private const val RECONNECT_DELAY_MS = 5_000

  private var ws: WebSocket = connect()

  private val packetsMutableFlow = MutableSharedFlow<EventServerPacket>()
  val packetsFlow: SharedFlow<EventServerPacket>
    get() = packetsMutableFlow.asSharedFlow()

  /** Open a websocket, and automatically reconnect if it closes (e.g. server restart, proxy timeout). */
  private fun connect(): WebSocket {
    val socket = WebSocket(ApplicationProperties.websocketServerUrl)
    socket.onmessage = ::handleMessageEvent
    socket.onerror = ::handleError
    socket.onclose = {
      println("[WebsocketService] closed, reconnecting in ${RECONNECT_DELAY_MS}ms")
      window.setTimeout({ ws = connect() }, RECONNECT_DELAY_MS)
    }
    println("[WebsocketService] connecting ${socket.url}")
    return socket
  }

  private fun handleMessageEvent(msg: MessageEvent) {

    val data = (msg.data as? String)?.trim()

    if (
      data != null
      && data.startsWith("{")
      && data.endsWith("}")
    ) {

      println("[WebsocketService.handleMessageEvent] ${data.replace('\n', ' ')}")

      val packet: EventServerPacket? = runCatching {
        jsonMapper.decodeFromString(EventServerPacket.serializer(), data)
      }.getOrElse {
        null
      }

      if (packet != null) {

        launch { packetsMutableFlow.emit(packet) }

        when (packet) {
          is EventServerPacket.ChunkTileSaved -> {}

          is EventServerPacket.Kafkatorio     ->
            when (val packetData = packet.packet.data) {
              is PlayerUpdate              ->
                App.siteStateStore.dispatch(
                  SiteAction.FactorioUpdate.Player(packet.packet.tick, packetData)
                )

              is ConfigurationUpdate,
              is ConsoleChatUpdate,
              is ConsoleCommandUpdate,
              is EntityUpdate,
              is MapChunkEntityUpdate,
              is MapChunkResourceUpdate,
              is MapChunkTileUpdate,
              is PrototypesUpdate,
              is ResearchUpdate,
              is ProductionUpdate,
              is SurfaceUpdate,
              is KafkatorioPacketDataError -> {
                // to be continued...
              }
            }
        }
      } else {
        println("[WebsocketService.handleMessageEvent] unknown json message ${msg.data}")
      }
    } else {
      println("[WebsocketService.handleMessageEvent] non-json message ${msg.data}")
    }
  }

  private fun handleError(error: Event) {
    println("ws error: ${JSON.stringify(error)}")
  }
}
