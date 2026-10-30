package io.github.vatsarishabh22.reviewlens

/**
 * What one function's chip says (spec/impact.md, "Words"): a dot per place on the checklist ([dots], true when ticked),
 * the [text], an optional [link] after it, [done] once every place is ticked, [busy] while the search runs, and
 * [foldable] when it unfolds into a peek.
 */
data class Chip(val dots: List<Boolean>, val text: String, val link: String? = null, val done: Boolean = false, val busy: Boolean = false, val foldable: Boolean = false)

/** The places the reviewer ticks: every listed group's, none of a widely used group's. */
fun Uses.Found.checklist(): List<Place> = listOf(code, tests).filterIsInstance<PlaceGroup.Listed>().flatMap { it.places }

/** How many places use it, a widely used group's included ("11 places use this" for 3 listed and 8 counted). */
fun Uses.Found.total(): Int = checklist().size + listOf(code, tests).filterIsInstance<PlaceGroup.Wide>().sumOf { it.count }

/**
 * The chip for [impact] with the places in [checked] ticked; null when nothing outside the PR uses it (no chip). A
 * removed function has no line of its own, so its chip, in the strip above line 1, starts with its name.
 */
fun chipOf(impact: Impact, checked: Set<String>): Chip? {
    val removed = impact.change.kind == ChangeKind.REMOVED
    val name = if (removed) "${impact.change.name.substringBefore('#')} removed · " else ""
    return when (val uses = impact.uses) {
        Uses.Searching -> Chip(emptyList(), "${name}Finding places…", busy = true)
        // Find Usages needs the declaration; a removed function has none left to search from
        Uses.TooSlow -> Chip(emptyList(), if (removed) "${name}many places" else "Many places · ", link = "Open Find Usages".takeUnless { removed })
        is Uses.Found -> {
            if (impact.silent) return null
            val list = uses.checklist()
            val ticks = list.map { it.key in checked }
            when {
                list.isNotEmpty() && ticks.all { it } -> Chip(ticks, name + (if (list.size == 1) "1 place checked" else "All ${list.size} places checked").let { if (removed) it.lowercase() else it }, done = true, foldable = true)
                removed -> Chip(ticks, name + (if (uses.total() == 1) "1 place still uses it" else "${uses.total()} places still use it"), foldable = true)
                else -> Chip(ticks, (if (uses.total() == 1) "1 place uses this" else "${uses.total()} places use this") + " · " + impact.change.kind.label, foldable = true)
            }
        }
    }
}
