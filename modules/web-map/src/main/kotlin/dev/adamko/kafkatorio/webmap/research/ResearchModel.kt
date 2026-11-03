package dev.adamko.kafkatorio.webmap.research

import dev.adamko.kafkatorio.schema.packets.ResearchUpdate
import dev.adamko.kafkatorio.webmap.services.TechnologyInfo
import kotlin.math.max
import kotlin.math.pow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull


enum class TechnologyStatus(val label: String) {
  RESEARCHING("Researching"),
  QUEUED("Queued"),
  AVAILABLE("Available"),
  LOCKED("Locked"),
  RESEARCHED("Researched"),
}


/** Science packs, by name, and the amount of each */
typealias SciencePackCost = Map<String, Double>


/**
 * The technology tree, combined with the research state of a force.
 */
class ResearchModel(
  val technologies: Map<String, TechnologyInfo>,
  val research: ResearchUpdate?,
) {

  private val researched: Set<String> = research?.researched?.toSet() ?: emptySet()
  private val queue: List<String> = research?.queue?.toList() ?: emptyList()
  private val priceMultiplier: Double = research?.technologyPriceMultiplier ?: 1.0

  /** The technologies that need each technology */
  val successors: Map<String, List<String>> = buildMap<String, MutableList<String>> {
    technologies.forEach { (name, tech) ->
      tech.prerequisites.forEach { prerequisite -> getOrPut(prerequisite) { mutableListOf() }.add(name) }
    }
  }

  /** The length of the longest chain of prerequisites, so early technologies sort first */
  val depth: Map<String, Int> = buildMap {
    fun depthOf(name: String, visiting: Set<String>): Int = getOrElse(name) {
      val prerequisites = technologies[name]?.prerequisites.orEmpty().filter { it !in visiting }
      val depth = prerequisites.maxOfOrNull { depthOf(it, visiting + name) + 1 } ?: 0
      put(name, depth)
      depth
    }
    technologies.keys.forEach { depthOf(it, emptySet()) }
  }

  val status: Map<String, TechnologyStatus> = technologies.mapValues { (name, tech) ->
    when {
      name in researched                                   -> TechnologyStatus.RESEARCHED
      name == research?.current                            -> TechnologyStatus.RESEARCHING
      name in queue                                        -> TechnologyStatus.QUEUED
      !tech.disabled && tech.prerequisites.all { it in researched } -> TechnologyStatus.AVAILABLE
      else                                                 -> TechnologyStatus.LOCKED
    }
  }

  /** Science packs, sorted by how early in the tech tree they're first needed */
  val sciencePacks: List<String> =
    technologies.flatMap { (name, tech) -> tech.packs().map { pack -> pack to (depth[name] ?: 0) } }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, depths) -> depths.min() }
      .entries
      .sortedWith(compareBy({ it.value }, { it.key }))
      .map { it.key }

  val researchedCount: Int get() = researched.count { it in technologies }

  /** Technologies that can be researched more than once never finish, so don't count them */
  val finiteCount: Int get() = technologies.count { (_, tech) -> !tech.isInfinite() }


  /** The level that will be researched next, for technologies with levels */
  fun level(name: String): Int? {
    val tech = technologies[name] ?: return null
    if (tech.maxLevel == null) return null
    return research?.levels?.get(name) ?: tech.level ?: 1
  }


  /** The science packs needed to research the next level of a technology */
  fun cost(name: String): SciencePackCost {
    val tech = technologies[name] ?: return emptyMap()
    return tech.levelCost(level(name) ?: tech.level ?: 1)
  }


  /** Seconds per unit */
  fun timePerUnit(name: String): Double? = technologies[name]?.cost?.time

  /** The number of units of the next level */
  fun units(name: String): Double? {
    val tech = technologies[name] ?: return null
    return tech.units(level(name) ?: tech.level ?: 1)
  }


  /**
   * The science packs needed to research a technology, including all its prerequisites that
   * aren't researched yet.
   */
  fun costToReach(name: String): Pair<SciencePackCost, Int> {
    val needed = mutableSetOf<String>()
    fun visit(tech: String) {
      if (status[tech] == TechnologyStatus.RESEARCHED || !needed.add(tech)) return
      technologies[tech]?.prerequisites?.forEach { visit(it) }
    }
    visit(name)
    return needed.map { cost(it) }.sum() to needed.size
  }


  /**
   * The science packs needed to research everything that's left, excluding infinite
   * technologies. For technologies with a limited number of levels, include all levels left.
   */
  fun remainingCost(): SciencePackCost =
    technologies.filter { (name, tech) -> status[name] != TechnologyStatus.RESEARCHED && !tech.isInfinite() }
      .map { (name, tech) ->
        val maxLevel = tech.maxLevel?.intOrNull
        val level = level(name)
        if (maxLevel != null && level != null) {
          (level..maxLevel).map { tech.levelCost(it) }.sum()
        } else {
          cost(name)
        }
      }.sum()


  private fun TechnologyInfo.levelCost(level: Int): SciencePackCost {
    val units = units(level) ?: return emptyMap()
    return packAmounts().mapValues { (_, amount) -> amount * units }
  }


  private fun TechnologyInfo.units(level: Int): Double? {
    val cost = cost ?: return null
    val units = cost.count ?: cost.countFormula?.let { evaluateCountFormula(it, level) } ?: return null
    val multiplier = if (cost.ignoreMultiplier) 1.0 else priceMultiplier
    return max(1.0, units * multiplier)
  }
}


private fun TechnologyInfo.isInfinite(): Boolean = maxLevel?.contentOrNull == "infinite"


/** Science pack -> amount per unit */
fun TechnologyInfo.packAmounts(): Map<String, Double> =
  cost?.ingredients.orEmpty().mapNotNull { ingredient ->
    val pack = ingredient.getOrNull(0)?.contentOrNull ?: return@mapNotNull null
    val amount = ingredient.getOrNull(1)?.doubleOrNull ?: 1.0
    pack to amount
  }.toMap()


fun TechnologyInfo.packs(): Set<String> = packAmounts().keys


private fun List<SciencePackCost>.sum(): SciencePackCost = buildMap {
  this@sum.forEach { cost ->
    cost.forEach { (pack, amount) -> put(pack, (get(pack) ?: 0.0) + amount) }
  }
}


/**
 * Evaluate a technology count formula, like `2^(L-6)*1000`.
 *
 * Factorio's formulas support `+ - * / ^`, brackets, numbers, and `L` (or `l`) for the level.
 */
fun evaluateCountFormula(formula: String, level: Int): Double? = runCatching {
  FormulaParser(formula.replace(" ", ""), level.toDouble()).parse()
}.getOrNull()


private class FormulaParser(private val input: String, private val level: Double) {
  private var pos = 0

  fun parse(): Double {
    val value = expression()
    require(pos == input.length) { "unexpected '${input[pos]}' in $input" }
    return value
  }

  private fun expression(): Double {
    var value = term()
    while (pos < input.length && input[pos] in "+-") {
      value = if (input[pos++] == '+') value + term() else value - term()
    }
    return value
  }

  private fun term(): Double {
    var value = power()
    while (pos < input.length && input[pos] in "*/") {
      value = if (input[pos++] == '*') value * power() else value / power()
    }
    return value
  }

  private fun power(): Double {
    val base = unary()
    if (pos < input.length && input[pos] == '^') {
      pos++
      return base.pow(power()) // right associative
    }
    return base
  }

  private fun unary(): Double {
    if (pos < input.length && input[pos] == '-') {
      pos++
      return -unary()
    }
    return atom()
  }

  private fun atom(): Double {
    require(pos < input.length) { "unexpected end of $input" }
    val c = input[pos]
    return when {
      c == '('             -> {
        pos++
        val value = expression()
        require(input.getOrNull(pos) == ')') { "missing ')' in $input" }
        pos++
        value
      }

      c == 'L' || c == 'l' -> {
        pos++
        level
      }

      c.isDigit() || c == '.' -> {
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
        input.substring(start, pos).toDouble()
      }

      else                 -> error("unexpected '$c' in $input")
    }
  }
}
