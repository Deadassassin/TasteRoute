package space.gexemy.tasteroute.data

import java.util.Calendar
import kotlin.random.Random

/**
 * The suggestion chips above an empty conversation.
 *
 * They used to be four hardcoded strings, which meant the assistant appeared to have exactly four
 * things it could do — and none of them had anything to do with the person reading them. These are
 * built from the taste profile the app already holds and the time of day, then shuffled, so the
 * chips are different every time the screen opens and always about food this person eats.
 *
 * Two of the four are deliberately open questions rather than place searches. If every chip
 * returned a list, nobody would ever find out they can just ask something.
 */
object Openers {

    private data class Window(val meal: String, val phrase: String)

    private fun windowFor(hour: Int) = when (hour) {
        in 5..10 -> Window("breakfast", "this morning")
        in 11..14 -> Window("lunch", "for lunch")
        in 15..17 -> Window("a snack", "this afternoon")
        in 18..22 -> Window("dinner", "tonight")
        else -> Window("something late", "at this hour")
    }

    /** Asks that work with no profile at all, so a brand-new install never sees a thin list. */
    private val universal = listOf(
        "What's actually good around here?",
        "Talk me out of ordering the usual",
        "What should I try that I never order?",
        "How do I order well at a place I've never been?",
        "What's worth the queue and what isn't?",
    )

    fun forProfile(
        profile: TasteProfile = AppState.profile,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        random: Random = Random(System.currentTimeMillis()),
    ): List<String> {
        val w = windowFor(hour)
        val cuisine = profile.preferredCuisines.randomOrNull(random)
        val vibe = profile.vibeTags.randomOrNull(random)?.lowercase()
        val diet = profile.dietaryRestrictions.randomOrNull(random)?.lowercase()

        val placey = buildList {
            add("What should I eat ${w.phrase}?")
            cuisine?.let { add("Good $it near me, ${w.phrase}") }
            vibe?.let { add("Somewhere $vibe for ${w.meal}") }
            if (profile.priceComfort <= 2) add("Cheap and actually good, ${w.phrase}")
            else add("Worth spending on ${w.phrase}")
        }

        val talky = buildList {
            cuisine?.let { add("What should I order at a $it place?") }
            diet?.let { add("How do I keep $diet meals interesting?") }
            addAll(universal)
        }

        // Two of each, so the chips never collapse into four variations of "find me a restaurant".
        return (placey.shuffled(random).take(2) + talky.shuffled(random).take(2))
            .distinct()
            .shuffled(random)
    }
}
