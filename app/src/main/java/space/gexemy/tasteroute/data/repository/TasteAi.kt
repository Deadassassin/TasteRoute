package space.gexemy.tasteroute.data.repository

import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.network.NimClient
import space.gexemy.tasteroute.data.network.ParsedTaste

/**
 * Turns a free-text description of what someone likes into the structured [TasteProfile]
 */
object TasteAi {

    suspend fun parse(text: String, current: TasteProfile): TasteProfile {
        val parsed = NimClient.parseTaste(text, ALL_CUISINES, ALL_DIETARY, ALL_VIBES)
        return current.copy(
            preferredCuisines = parsed.cuisines.pick(ALL_CUISINES).ifEmpty { current.preferredCuisines },
            dietaryRestrictions = parsed.diets.pick(ALL_DIETARY),
            vibeTags = parsed.vibes.pick(ALL_VIBES).ifEmpty { current.vibeTags },
            priceComfort = parsed.priceComfort.takeIf { it in 1..4 } ?: current.priceComfort,
        )
    }

    fun merge(parsed: ParsedTaste, current: TasteProfile): TasteProfile = current.copy(
        preferredCuisines = (current.preferredCuisines + parsed.cuisines.pick(ALL_CUISINES)).distinct(),
        dietaryRestrictions = (current.dietaryRestrictions + parsed.diets.pick(ALL_DIETARY)).distinct(),
        vibeTags = (current.vibeTags + parsed.vibes.pick(ALL_VIBES)).distinct(),
        priceComfort = parsed.priceComfort.takeIf { it in 1..4 } ?: current.priceComfort,
    )

    private fun List<String>.pick(allowed: List<String>) =
        mapNotNull { v -> allowed.firstOrNull { it.equals(v.trim(), ignoreCase = true) } }.distinct()

    fun summarizeList(p: TasteProfile): List<String> = buildList {
        if (p.preferredCuisines.isNotEmpty()) add(p.preferredCuisines.joinToString(", "))
        if (p.vibeTags.isNotEmpty()) add(p.vibeTags.joinToString(", ").lowercase())
        add("${"$".repeat(p.priceComfort.coerceIn(1, 4))} budget")
        if (p.dietaryRestrictions.isNotEmpty()) add(p.dietaryRestrictions.joinToString(", "))
    }

    fun summarize(p: TasteProfile): String = summarizeList(p).joinToString(" · ")
}
