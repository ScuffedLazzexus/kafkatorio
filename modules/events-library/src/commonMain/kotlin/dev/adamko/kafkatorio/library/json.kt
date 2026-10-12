package dev.adamko.kafkatorio.library

import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule


val jsonMapperKafkatorio = Json {
  prettyPrint = true
  prettyPrintIndent = "  "

  serializersModule = SerializersModule {
//    contextual(List::class) { args -> FactorioListJsonSerializer(args[0]) }
  }
}


/**
 * Implements [Collection] by hand, rather than via delegation (`by list`), because delegation
 * triggers a longstanding Kotlin compiler bug ("accidental override") on the generic `toArray`
 * method - see [KT-3546](https://youtrack.jetbrains.com/issue/KT-3546). A hand-written
 * implementation doesn't hit that bug, since it's specific to the delegation codegen path.
 *
 * @see LuaJsonListSerializer
 */
@Serializable(with = LuaJsonListSerializer::class)
@JvmInline
value class LuaJsonList<T>(val list: List<T>) : Collection<T> {
  override val size: Int get() = list.size
  override fun isEmpty(): Boolean = list.isEmpty()
  override fun contains(element: T): Boolean = list.contains(element)
  override fun containsAll(elements: Collection<T>): Boolean = list.containsAll(elements)
  override fun iterator(): Iterator<T> = list.iterator()
}


/**
 * Factorio outputs **empty** lists `[]` as *empty* Json objects `{}`.
 *
 * When deserializing JSON, this serializer transforms any incoming empty objects to empty
 * [array][JsonArray]s.
 */
class LuaJsonListSerializer<T>(
  elementSerializer: KSerializer<T>
) : KSerializer<LuaJsonList<T>> {

  private val listSerializer: KSerializer<List<T>> = ListSerializer(elementSerializer)

  override val descriptor: SerialDescriptor =
    SerialDescriptor("kafkatorio.LuaList", listSerializer.descriptor)

  override fun deserialize(decoder: Decoder): LuaJsonList<T> = LuaJsonList(
    when (decoder) {
      is JsonDecoder -> deserializeJson(decoder)
      else           -> decoder.decodeSerializableValue(listSerializer)
    }
  )

  private fun deserializeJson(decoder: JsonDecoder): List<T> =
    when (val element = decoder.decodeJsonElement()) {
      is JsonObject -> when {
        element.entries.isEmpty() -> emptyList()
        else                      -> deserializeError(
          "expected empty Json object, but contained ${element.entries.size} entries ${element.entries}"
        )
      }
      is JsonArray  -> decoder.json.decodeFromJsonElement(listSerializer, element)
      else          -> deserializeError(
        "expected empty Json object or a Json array, but was $element"
      )
    }

  override fun serialize(encoder: Encoder, value: LuaJsonList<T>) {
    encoder.encodeSerializableValue(listSerializer, value.list)
  }

  companion object {
    private fun deserializeError(msg: String): Nothing = throw SerializationException(
      "Error deserializing LuaList: $msg"
    )
  }
}
