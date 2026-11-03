package dev.adamko.kafkatorio.processor.admin

import dev.adamko.kafkatorio.processor.config.ApplicationProperties
import dev.adamko.kafkatorio.processor.config.allTopics
import dev.adamko.kafkatorio.processor.config.retainedForeverTopics
import java.util.concurrent.TimeUnit
import mu.KotlinLogging
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AlterConfigOp
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.CreateTopicsResult
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.TopicListing
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig


class KafkatorioKafkaAdmin(
  appProps: ApplicationProperties,
) {

  private val logger = KotlinLogging.logger {}

  init {
    println("Creating KafkatorioKafkaAdmin with config: ${appProps.kafkaAdminConfig}")
  }

  private val kafkaAdmin: Admin = Admin.create(appProps.kafkaAdminConfig)

  fun createKafkatorioTopics() {

    val currentTopics = currentTopics().map { it.name() }

    val kafkatorioTopics = allTopics() - currentTopics.toSet()

    if (kafkatorioTopics.isNotEmpty()) {
      logger.info("Creating ${kafkatorioTopics.size} topics")
      val result = kafkaAdmin.createTopics { kafkatorioTopics }

      // wait for all brokers to become aware of the created topics
      Thread.sleep(TimeUnit.SECONDS.toMillis(5))

      result.values().forEach {
        logger.info("Created topic: ${it.key} ${it.value.get()}")
      }

      Thread.sleep(TimeUnit.SECONDS.toMillis(5))

      logger.info("Listing topics")
      kafkaAdmin.listTopics()
        .listings()
        .get()
        .forEach {
          logger.info("Topic: $it")
        }
    }

    retainTopicsForever()
  }


  /** Disable time-based deletion for [retainedForeverTopics] (including existing topics). */
  private fun retainTopicsForever() {
    val retainForever = AlterConfigOp(
      ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, "-1"),
      AlterConfigOp.OpType.SET,
    )
    val configs = retainedForeverTopics.associate { topic ->
      ConfigResource(ConfigResource.Type.TOPIC, topic) to listOf(retainForever)
    }
    kafkaAdmin.incrementalAlterConfigs(configs).all().get()
    logger.info("Retaining topics forever: $retainedForeverTopics")
  }

  private fun Admin.createTopics(
    numPartitions: Int = 3,
    replicationFactor: Short = 1,
    topicNames: () -> Collection<String>,
  ): CreateTopicsResult =
    createTopics(
      topicNames()
        .distinct()
        .map {
          NewTopic(it, numPartitions, replicationFactor)
//            .configs(mapOf("cleanup.policy" to "compact"))
        }
    )

  private fun currentTopics(): MutableCollection<TopicListing> {
    return kafkaAdmin.listTopics()
      .listings()
      .get()
  }

//  fun topicNameBase(packetType: KafkatorioPacketDataType): String =
//    "kafkatorio.${packetType.name}"
}
