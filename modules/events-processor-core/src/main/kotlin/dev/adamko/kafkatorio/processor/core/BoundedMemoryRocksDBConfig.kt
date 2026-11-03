package dev.adamko.kafkatorio.processor.core

import org.apache.kafka.streams.state.RocksDBConfigSetter
import org.rocksdb.BlockBasedTableConfig
import org.rocksdb.Cache
import org.rocksdb.LRUCache
import org.rocksdb.Options
import org.rocksdb.RocksDB
import org.rocksdb.WriteBufferManager


/**
 * Make all RocksDB state stores share one fixed amount of off-heap memory.
 *
 * By default every store (and Kafkatorio has dozens: one per store, per partition) gets its own
 * block cache and memtables, so memory use grows with the number of stores. Here the block cache
 * and the memtables of every store come out of one shared cache.
 *
 * Based on the Kafka Streams docs, "Bounded memory usage".
 *
 * Enable with the Kafka Streams config `rocksdb.config.setter`.
 */
class BoundedMemoryRocksDBConfig : RocksDBConfigSetter {

  override fun setConfig(storeName: String, options: Options, configs: Map<String, Any>) {
    val tableConfig = options.tableFormatConfig() as BlockBasedTableConfig

    tableConfig.setBlockCache(cache)
    // count index and filter blocks towards the cache, but keep them in it
    tableConfig.setCacheIndexAndFilterBlocks(true)
    tableConfig.setCacheIndexAndFilterBlocksWithHighPriority(true)
    tableConfig.setPinTopLevelIndexAndFilter(true)
    options.setTableFormatConfig(tableConfig)

    options.setWriteBufferManager(writeBufferManager)
    options.setMaxWriteBufferNumber(MEMTABLES_PER_STORE)
    options.setWriteBufferSize(MEMTABLE_SIZE)
  }

  override fun close(storeName: String, options: Options) {
    // the cache and write buffer manager are shared by all stores, so don't close them
  }

  companion object {
    private const val MB = 1024L * 1024L

    /** Total off-heap memory for all stores, including the memtables */
    private val TOTAL_MEMORY = (System.getenv("ROCKSDB_TOTAL_MEMORY_MB")?.toLongOrNull() ?: 256L) * MB

    /** How much of [TOTAL_MEMORY] may be used by memtables (writes not yet flushed to disk) */
    private val TOTAL_MEMTABLE_MEMORY = TOTAL_MEMORY / 4

    private const val INDEX_FILTER_BLOCK_RATIO = 0.1
    private const val MEMTABLES_PER_STORE = 2
    private const val MEMTABLE_SIZE = 8 * MB

    init {
      // the shared cache is created before any store, so the native library might not be loaded yet
      RocksDB.loadLibrary()
    }

    private val cache: Cache = LRUCache(TOTAL_MEMORY, -1, false, INDEX_FILTER_BLOCK_RATIO)
    private val writeBufferManager = WriteBufferManager(TOTAL_MEMTABLE_MEMORY, cache)
  }
}
