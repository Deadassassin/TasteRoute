package space.gexemy.tasteroute.data.model

import space.gexemy.tasteroute.data.state.AppState

/**
 * The dish to order, not the restaurant to go to.
 *
 * Two sources, in this order:
 *  1. A real menu item, when a source actually harvested one — [RestaurantResult.menuHighlights].
 *  2. Otherwise a suggestion built from the kitchen's cuisine and this person's own taste.
 *
 * The second is a recommendation FOR THEM, never a claim about the venue's menu, and the label
 * says so ("Your pick" vs "Popular"). A restaurant we have no menu for cannot be quoted a menu.
 *
 * Selection is deterministic per place — same place, same taste, same dish every time — but
 * spread across places by hashing the id. A list where every Thai restaurant suggests pad thai
 * is a lookup table; this reads as a choice.
 */
object DishPicks {

    /** [contains] is matched against the user's allergen list; [veg] means safe for vegetarians. */
    private data class Dish(val name: String, val contains: Set<String> = emptySet(), val veg: Boolean = false)

    private fun d(name: String, vararg contains: String) = Dish(name, contains.toSet())
    private fun v(name: String, vararg contains: String) = Dish(name, contains.toSet(), veg = true)

    private val menus: Map<String, List<Dish>> = mapOf(
        "thai" to listOf(
            d("Pad Thai", "Peanuts", "Eggs", "Fish", "Shellfish"), v("Green curry"), d("Massaman curry", "Peanuts"),
            v("Som tam", "Peanuts", "Fish"), d("Khao soi", "Eggs"), v("Pad see ew", "Soy", "Wheat", "Gluten"),
        ),
        "japanese" to listOf(
            d("Chirashi bowl", "Fish"), d("Katsu curry", "Wheat", "Gluten", "Eggs"), d("Gyoza", "Wheat", "Gluten", "Soy"),
            v("Vegetable tempura", "Wheat", "Gluten"), d("Tonkotsu ramen", "Eggs", "Soy", "Wheat", "Gluten"),
        ),
        "sushi" to listOf(
            d("Omakase", "Fish", "Shellfish"), d("Salmon nigiri", "Fish"), d("Spicy tuna roll", "Fish", "Soy"),
            v("Cucumber roll"), d("Chirashi bowl", "Fish"),
        ),
        "ramen" to listOf(
            d("Tonkotsu ramen", "Eggs", "Soy", "Wheat", "Gluten"), v("Miso ramen", "Soy", "Wheat", "Gluten"),
            d("Tsukemen", "Eggs", "Soy", "Wheat", "Gluten"), d("Karaage", "Wheat", "Gluten", "Soy"),
        ),
        "chinese" to listOf(
            v("Mapo tofu", "Soy"), d("Dan dan noodles", "Peanuts", "Sesame", "Wheat", "Gluten"),
            d("Xiao long bao", "Wheat", "Gluten"), v("Salt and pepper tofu", "Soy"), d("Char siu", "Soy"),
        ),
        "korean" to listOf(
            v("Bibimbap", "Eggs", "Sesame", "Soy"), d("Korean fried chicken", "Wheat", "Gluten", "Soy"),
            d("Bulgogi", "Soy", "Sesame"), v("Kimchi jjigae", "Fish", "Soy"), d("Tteokbokki", "Fish", "Soy"),
        ),
        "vietnamese" to listOf(
            d("Pho tai", "Fish"), d("Banh mi", "Wheat", "Gluten", "Eggs"), v("Bun chay", "Peanuts"),
            d("Bun bo hue", "Fish", "Shellfish"), v("Summer rolls", "Peanuts", "Fish"),
        ),
        "indian" to listOf(
            v("Paneer tikka", "Dairy"), d("Butter chicken", "Dairy", "Tree nuts"), v("Chana masala"),
            v("Masala dosa"), d("Rogan josh", "Dairy"), v("Dal makhani", "Dairy"),
        ),
        "mexican" to listOf(
            d("Al pastor tacos"), v("Bean and cheese burrito", "Dairy", "Wheat", "Gluten"),
            d("Carnitas tacos"), v("Elote", "Dairy", "Eggs"), d("Birria quesadilla", "Dairy", "Wheat", "Gluten"),
        ),
        "italian" to listOf(
            v("Cacio e pepe", "Dairy", "Wheat", "Gluten", "Eggs"), d("Carbonara", "Dairy", "Eggs", "Wheat", "Gluten"),
            v("Margherita pizza", "Dairy", "Wheat", "Gluten"), d("Ragu tagliatelle", "Wheat", "Gluten", "Eggs"),
            v("Melanzane parmigiana", "Dairy"),
        ),
        "pizza" to listOf(
            v("Margherita", "Dairy", "Wheat", "Gluten"), d("Diavola", "Dairy", "Wheat", "Gluten"),
            v("Marinara", "Wheat", "Gluten"), d("Pepperoni slice", "Dairy", "Wheat", "Gluten"),
        ),
        "burger" to listOf(
            d("Double cheeseburger", "Dairy", "Wheat", "Gluten", "Sesame"), v("Halloumi burger", "Dairy", "Wheat", "Gluten"),
            d("Bacon smash", "Dairy", "Wheat", "Gluten", "Sesame"), d("Chicken sandwich", "Wheat", "Gluten", "Eggs"),
        ),
        "american" to listOf(
            d("Buttermilk fried chicken", "Dairy", "Wheat", "Gluten", "Eggs"), d("Smash burger", "Dairy", "Wheat", "Gluten"),
            v("Mac and cheese", "Dairy", "Wheat", "Gluten"), d("Brisket plate"),
        ),
        "bbq" to listOf(
            d("Brisket plate"), d("Burnt ends"), d("Pulled pork sandwich", "Wheat", "Gluten"),
            v("Smoked mac and cheese", "Dairy", "Wheat", "Gluten"), d("Baby back ribs"),
        ),
        "steak_house" to listOf(
            d("Ribeye"), d("Steak frites"), d("Bone-in sirloin"), v("Creamed spinach", "Dairy"),
        ),
        "seafood" to listOf(
            d("Grilled whole fish", "Fish"), d("Fish and chips", "Fish", "Wheat", "Gluten"),
            d("Garlic prawns", "Shellfish", "Dairy"), d("Ceviche", "Fish"), d("Clam linguine", "Shellfish", "Wheat", "Gluten"),
        ),
        "mediterranean" to listOf(
            v("Mezze plate", "Sesame", "Dairy"), d("Chicken shawarma", "Sesame", "Wheat", "Gluten"),
            v("Falafel wrap", "Sesame", "Wheat", "Gluten"), v("Halloumi and fig salad", "Dairy"),
        ),
        "greek" to listOf(
            d("Chicken souvlaki", "Wheat", "Gluten", "Dairy"), v("Spanakopita", "Dairy", "Wheat", "Gluten", "Eggs"),
            v("Greek salad", "Dairy"), d("Lamb gyros", "Wheat", "Gluten", "Dairy"),
        ),
        "turkish" to listOf(
            d("Adana kebab", "Wheat", "Gluten"), v("Lahmacun", "Wheat", "Gluten"),
            v("Menemen", "Eggs"), d("Iskender", "Dairy", "Wheat", "Gluten"),
        ),
        "kebab" to listOf(
            d("Adana kebab", "Wheat", "Gluten"), d("Chicken shish", "Wheat", "Gluten"),
            v("Falafel wrap", "Sesame", "Wheat", "Gluten"),
        ),
        "french" to listOf(
            d("Steak frites"), d("Duck confit"), v("French onion soup", "Dairy", "Wheat", "Gluten"),
            d("Moules frites", "Shellfish", "Dairy"), v("Croque végétarien", "Dairy", "Wheat", "Gluten", "Eggs"),
        ),
        "spanish" to listOf(
            v("Patatas bravas", "Eggs"), d("Gambas al ajillo", "Shellfish"), v("Tortilla española", "Eggs"),
            d("Jamón ibérico"), d("Seafood paella", "Shellfish", "Fish"),
        ),
        "cafe" to listOf(
            v("Flat white", "Dairy"), v("Avocado toast", "Wheat", "Gluten"), v("Almond croissant", "Tree nuts", "Dairy", "Wheat", "Gluten", "Eggs"),
            v("Shakshuka", "Eggs"), v("Banana bread", "Wheat", "Gluten", "Eggs", "Dairy"),
        ),
        "coffee_shop" to listOf(
            v("Cortado", "Dairy"), v("Cold brew"), v("Pain au chocolat", "Dairy", "Wheat", "Gluten", "Eggs"),
        ),
        "bakery" to listOf(
            v("Sourdough loaf", "Wheat", "Gluten"), v("Cardamom bun", "Dairy", "Wheat", "Gluten", "Eggs"),
            v("Almond croissant", "Tree nuts", "Dairy", "Wheat", "Gluten", "Eggs"), v("Canelé", "Dairy", "Eggs", "Wheat", "Gluten"),
        ),
        "dessert" to listOf(
            v("Basque cheesecake", "Dairy", "Eggs", "Wheat", "Gluten"), v("Pistachio gelato", "Dairy", "Tree nuts"),
            v("Tiramisu", "Dairy", "Eggs", "Wheat", "Gluten"),
        ),
        "breakfast" to listOf(
            v("Eggs Benedict", "Eggs", "Dairy", "Wheat", "Gluten"), v("Buttermilk pancakes", "Dairy", "Eggs", "Wheat", "Gluten"),
            v("Shakshuka", "Eggs"), d("Breakfast burrito", "Eggs", "Dairy", "Wheat", "Gluten"),
        ),
        "sandwich" to listOf(
            d("Italian sub", "Wheat", "Gluten", "Dairy"), v("Caprese focaccia", "Dairy", "Wheat", "Gluten"),
            d("Pastrami on rye", "Wheat", "Gluten"),
        ),
        "vegan" to listOf(
            v("Mushroom birria tacos"), v("Jackfruit bao", "Soy", "Wheat", "Gluten"), v("Cashew mac", "Tree nuts"),
            v("Smash patty", "Soy", "Wheat", "Gluten"),
        ),
        "vegetarian" to listOf(
            v("Halloumi skewers", "Dairy"), v("Mushroom risotto", "Dairy"), v("Paneer tikka", "Dairy"),
            v("Aubergine katsu", "Wheat", "Gluten"),
        ),
    )

    /** Nothing sensible to suggest beats suggesting something generic. */
    private val fallback = listOf(v("Whatever the kitchen is known for"))

    /**
     * A dish, and whether it is a real menu item or a suggestion. Null when the place's cuisine is
     * unknown and no menu was harvested — a card with no dish line is better than a guessed one.
     */
    data class Pick(val dish: String, val fromMenu: Boolean) {
        // "On the menu" is exactly what a harvested MenuItem proves and nothing more. "Popular"
        // would have implied we know what sells there, which no source in this app can tell us.
        val label: String get() = if (fromMenu) "On the menu" else "Your pick"
    }

    fun suggest(
        result: RestaurantResult,
        profile: TasteProfile = AppState.profile,
        allergens: List<String> = AppState.allergens.toList(),
    ): Pick? {
        result.menuHighlights.firstOrNull()?.let { return Pick(it, fromMenu = true) }

        val vegetarianOnly = profile.dietaryRestrictions.any { it.equals("Vegan", true) || it.equals("Vegetarian", true) }
        val pool = result.cuisineTags
            .flatMap { it.split(';', ',') }
            .flatMap { menus[normalise(it)].orEmpty() }
            .distinct()
            .ifEmpty { if (result.cuisineTags.isEmpty()) return null else fallback }
            .filter { dish -> !vegetarianOnly || dish.veg }
            .filter { dish -> allergens.none { a -> dish.contains.any { it.equals(a, true) } } }
        if (pool.isEmpty()) return null

        // Taste shifts the starting point rather than filtering: two people standing in the same
        // restaurant should not be told to order the same thing.
        val taste = profile.preferredCuisines.joinToString(",") + profile.vibeTags.joinToString(",") + profile.priceComfort
        val seed = (result.id.hashCode().toLong() * 31 + taste.hashCode()) and 0x7fffffff
        return Pick(pool[(seed % pool.size).toInt()].name, fromMenu = false)
    }

    /** OSM cuisine tags arrive as `pizza;italian`, `Coffee Shop`, `steak house`. Flatten all of it. */
    private fun normalise(tag: String) = tag.trim().lowercase().replace(' ', '_').replace('-', '_')
}
