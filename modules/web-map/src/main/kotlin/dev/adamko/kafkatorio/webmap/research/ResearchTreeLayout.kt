package dev.adamko.kafkatorio.webmap.research

import kotlin.math.max
import kotlin.math.min


/** Where a technology is drawn in the tree, in pixels. [x] and [y] are its centre. */
data class TreeNodePosition(val x: Double, val y: Double, val level: Int)


/**
 * Lays out the technology tree from top to bottom, like the game's technology screen.
 *
 * Each technology goes in the row of its [ResearchModel.depth] (the longest chain of
 * prerequisites), so every prerequisite is above the technologies that need it.
 * Within a row, technologies are ordered and moved so they sit near the technologies they
 * connect to, which keeps most links short and reduces crossings.
 *
 * (Internally the rows are called columns, and positions within them y, then swapped at the end.)
 */
class ResearchTreeLayout(
  private val model: ResearchModel,
  /** Vertical distance between rows */
  private val levelSpacing: Double = 170.0,
  /** Horizontal distance between technologies in the same row */
  private val rowSpacing: Double = 128.0,
) {

  private val names: List<String> = model.technologies.keys.toList()

  private val prerequisites: Map<String, List<String>> = names.associateWith { name ->
    model.technologies.getValue(name).prerequisites.filter { it in model.technologies }
  }

  private val neighbours: Map<String, List<String>> = names.associateWith { name ->
    prerequisites.getValue(name) + model.successors[name].orEmpty()
  }

  val positions: Map<String, TreeNodePosition>

  val width: Double
  val height: Double

  init {
    val columns: List<MutableList<String>> = run {
      val maxDepth = names.maxOfOrNull { model.depth[it] ?: 0 } ?: 0
      val columns = List(maxDepth + 1) { mutableListOf<String>() }
      names.sortedWith(compareBy({ model.technologies[it]?.order ?: "" }, { it }))
        .forEach { columns[model.depth[it] ?: 0].add(it) }
      columns
    }

    // 1. order each column by the average position of its neighbours (barycentre heuristic)
    val rank = mutableMapOf<String, Double>()
    fun updateRanks() = columns.forEach { column ->
      column.forEachIndexed { i, name -> rank[name] = i - (column.size - 1) / 2.0 }
    }
    updateRanks()
    repeat(12) { iteration ->
      // sweep left-to-right, then right-to-left
      val order = if (iteration % 2 == 0) columns.indices else columns.indices.reversed()
      for (c in order) {
        val column = columns[c]
        val barycentre = column.associateWith { name ->
          val linked = if (iteration % 2 == 0) prerequisites.getValue(name) else model.successors[name].orEmpty()
          if (linked.isEmpty()) rank.getValue(name) else linked.sumOf { rank.getValue(it) } / linked.size
        }
        column.sortBy { barycentre.getValue(it) }
        column.forEachIndexed { i, name -> rank[name] = i - (column.size - 1) / 2.0 }
      }
    }

    // 2. move each technology towards its neighbours, keeping the order and the spacing
    val y = rank.mapValuesTo(mutableMapOf()) { (_, r) -> r * rowSpacing }
    repeat(16) { iteration ->
      val order = if (iteration % 2 == 0) columns.indices else columns.indices.reversed()
      for (c in order) {
        val column = columns[c]
        if (column.isEmpty()) continue
        val desired = column.map { name ->
          val linked = neighbours.getValue(name)
          if (linked.isEmpty()) y.getValue(name) else (y.getValue(name) + linked.sumOf { y.getValue(it) }) / (linked.size + 1)
        }
        packColumn(desired).forEachIndexed { i, newY -> y[column[i]] = newY }
      }
    }

    val minY = y.values.minOrNull() ?: 0.0
    val maxY = y.values.maxOrNull() ?: 0.0
    val padding = rowSpacing
    positions = names.associateWith { name ->
      val level = model.depth[name] ?: 0
      TreeNodePosition(
        x = padding + y.getValue(name) - minY,
        y = padding + level * levelSpacing,
        level = level,
      )
    }
    width = padding * 2 + (maxY - minY)
    height = padding * 2 + (columns.size - 1).coerceAtLeast(0) * levelSpacing
  }


  /**
   * Place technologies as close as possible to [desired], in the same order, at least
   * [rowSpacing] apart.
   */
  private fun packColumn(desired: List<Double>): List<Double> {
    val n = desired.size
    // push down from the top, and up from the bottom, then take the average
    val down = DoubleArray(n)
    for (i in 0 until n) down[i] = if (i == 0) desired[i] else max(desired[i], down[i - 1] + rowSpacing)
    val up = DoubleArray(n)
    for (i in n - 1 downTo 0) up[i] = if (i == n - 1) desired[i] else min(desired[i], up[i + 1] - rowSpacing)
    val result = DoubleArray(n) { (down[it] + up[it]) / 2 }
    // averaging can squeeze technologies together, so restore the spacing
    for (i in 1 until n) result[i] = max(result[i], result[i - 1] + rowSpacing)
    return result.toList()
  }
}
