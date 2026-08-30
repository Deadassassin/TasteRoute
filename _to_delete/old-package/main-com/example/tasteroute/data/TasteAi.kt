package com.example.tasteroute.data

/**
 * Turns a free-text description of what someone likes into the structured [TasteProfile]
 * the rest of the app already filters and scores on. The model is constrained to the app's
 * existing vocabularies so it can never introduce a category no screen knows how to render.
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

    /**
     * Fold a preference the assistant picked up mid-conversation into the saved profile.
     *
     * Additive, unlike [parse]. The taste screen is someone describing themselves from scratch, so
     * replacing is right there. A chat message is one remark laid on top of a profile that is
     * already correct — "I've gone vegetarian" must not quietly wipe the cuisines they chose last
     * week, and it is the same call that decides whether the whole app scores differently tomorrow.
     */
    fun merge(parsed: ParsedTaste, current: TasteProfile): TasteProfile = current.copy(
        preferredCuisines = (current.preferredCuisines + parsed.cuisines.pick(ALL_CUISINES)).distinct(),
        dietaryRestrictions = (current.dietaryRestrictions + parsed.diets.pick(ALL_DIETARY)).distinct(),
        vibeTags = (current.vibeTags + parsed.vibes.pick(ALL_VIBES)).distinct(),
        priceComfort = parsed.priceComfort.takeIf { it in 1..4 } ?: current.priceComfort,
    )

    /** Model output is advisory: keep only values that exactly match a known category. */
    private fun List<String>.pick(allowed: List<String>) =
        mapNotNull { v -> allowed.firstOrNull { it.equals(v.trim(), ignoreCase = true) } }.distinct()

    fun summarize(p: TasteProfile): String = buildList {
        if (p.preferredCuisines.isNotEmpty()) add(p.preferredCuisines.joinToString(", "))
        if (p.vibeTags.isNotEmpty()) add(p.vibeTags.joinToString(", ").lowercase())
        add("${"$".repeat(p.priceComfort.coerceIn(1, 4))} budget")
        if (p.dietaryRestrictions.isNotEmpty()) add(p.dietaryRestrictions.joinToString(", "))
    }.joinToString(" · ")
}
