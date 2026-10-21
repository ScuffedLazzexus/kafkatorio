package dev.adamko.kafkatorio.server.web.kafka

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.schema.common.FactorioServerId
import dev.adamko.kafkatorio.schema.packets.KafkatorioPacket
import dev.adamko.kafkatorio.schema.packets.KafkatorioPacketData
import dev.adamko.kafkatorio.server.web.config.jsonMapper
import java.time.Duration
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer


/**
 * Read all packets from the beginning of a topic, and keep following it.
 *
 * This is for small in-memory views of the data. Unlike Kafka Streams there's no state store, so
 * the whole topic is re-read on every startup.
 */
suspend fun followTopic(
  appProps: ApplicationProperties,
  topic: String,
  clientId: String,
  handle: (serverId: FactorioServerId, data: KafkatorioPacketData) -> Unit,
): Unit = withContext(Dispatchers.IO) {
  val properties = Properties().apply {
    putAll(appProps.kafkaStreamsConfig.filterKeys { it == ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG })
    // Kafkatorio processors write transactionally - only read committed data
    put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    put(ConsumerConfig.CLIENT_ID_CONFIG, clientId)
  }

  KafkaConsumer(properties, StringDeserializer(), StringDeserializer()).use { consumer ->
    val partitions = consumer.partitionsFor(topic).map { TopicPartition(it.topic(), it.partition()) }
    consumer.assign(partitions)
    consumer.seekToBeginning(partitions)
    println("[$clientId] consuming $topic, partitions: ${partitions.size}")

    while (isActive) {
      val records = runInterruptible { consumer.poll(Duration.ofSeconds(1)) }
      for (record in records) {
        runCatching {
          val key = record.key() ?: return@runCatching
          val value = record.value() ?: return@runCatching
          val serverId = jsonMapper.decodeFromString(FactorioServerId.serializer(), key)
          val packet = jsonMapper.decodeFromString(KafkatorioPacket.serializer(), value)
          handle(serverId, packet.data)
        }.onFailure { println("[$clientId] failed to read record: $it") }
      }
    }
  }
}
