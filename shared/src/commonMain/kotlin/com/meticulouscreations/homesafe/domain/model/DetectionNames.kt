package com.meticulouscreations.homesafe.domain.model

/**
 * Human wording for the keys Frigate attaches to a detection. Sub-labels come from the
 * classifier's category folders (`sarahs_tesla`), zones from config keys (`front_lawn`) —
 * both snake_case, neither meant for a sentence. Names that need punctuation or a capital
 * mid-word are spelled out here; everything else is humanised generically.
 */
private val KNOWN_SUB_LABELS = mapOf(
    "sarahs_tesla" to "Sarah's Tesla",
    "ron_judys_mercedes" to "Ron and Judy's Mercedes",
)

/**
 * Words that name a vehicle, by kind or by make. In a category like `andrews_tesla` the word just
 * before the first of these is the car's owner, which is how [subLabelDisplayName] knows where the
 * apostrophe the key lost belongs.
 */
private val VEHICLE_WORDS = setOf(
    "car", "truck", "van", "minivan", "suv", "jeep", "pickup", "sedan", "wagon", "coupe", "hatchback", "convertible",
    "bike", "motorcycle", "scooter", "rv", "camper", "trailer", "boat", "model",
    "tesla", "mercedes", "benz", "bmw", "audi", "honda", "toyota", "ford", "chevy", "chevrolet", "subaru", "lexus",
    "acura", "nissan", "mazda", "hyundai", "kia", "volvo", "volkswagen", "vw", "porsche", "rivian", "polestar", "lucid",
    "prius", "civic", "camry", "corolla", "accord", "outback", "highlander", "tacoma", "cybertruck", "mini", "dodge",
    "ram", "gmc", "buick", "cadillac", "lincoln", "infiniti", "genesis", "jaguar", "range", "fiat", "mitsubishi",
)

/** Kept in capitals when they turn up in a name: "Yaya's BMW", not "Yaya's Bmw". */
private val UPPERCASE_WORDS = setOf("bmw", "suv", "vw", "gmc", "rv")

/**
 * Given names that end in an s of their own. `james_car` is James's car, not Jame's, and so is
 * `jamess_car` — the key of "James's Car" once its apostrophe is gone.
 */
private val NAMES_ENDING_IN_S = setOf(
    "agnes", "alexis", "amos", "andreas", "carlos", "charles", "chris", "curtis", "cyrus", "dennis", "doris", "douglas",
    "elias", "ellis", "frances", "francis", "giles", "gladys", "gus", "hans", "iris", "james", "janis", "jess", "jesus",
    "jonas", "jules", "julius", "klaus", "les", "lewis", "lois", "louis", "lucas", "marcus", "markus", "mathias",
    "matthias", "miles", "morris", "moses", "myles", "niklas", "nicholas", "nicolas", "otis", "phyllis", "rhys", "ross",
    "russ", "silas", "thomas", "tobias", "travis", "wes", "willis",
)

/** Owners who are already plural, so the apostrophe goes after the s: "Parents' Car". */
private val PLURAL_OWNERS = setOf("parents", "grandparents", "kids", "neighbors", "neighbours", "inlaws")

/**
 * "sarahs_tesla" -> "Sarah's Tesla"; "andrew" -> "Andrew"; "delivery_van" -> "Delivery Van".
 *
 * Frigate keys can't hold an apostrophe, so the labelling screen's slug drops it: "Andrew's Tesla"
 * is filed as `andrews_tesla`. It is put back when the key has the shape of an owner and a vehicle
 * — a word ending in s just before the first vehicle word ([VEHICLE_WORDS]) — and only then: a
 * face called `andrews` stays "Andrews", and so does anything that isn't about a vehicle. Names
 * that end in an s of their own ([NAMES_ENDING_IN_S]) and plural owners ([PLURAL_OWNERS],
 * "In-Laws'") are spelled accordingly, and a word ending in a double s is left alone rather than
 * guessed at. A name that still comes out wrong can be spelled out in [KNOWN_SUB_LABELS], which
 * always wins.
 */
fun subLabelDisplayName(key: String): String {
    KNOWN_SUB_LABELS[key.lowercase()]?.let { return it }
    val words = key.split('_', '-').filter { it.isNotBlank() }.toMutableList()
    val vehicleAt = words.indexOfFirst { it.lowercase() in VEHICLE_WORDS }
    if (vehicleAt >= 1) {
        val ownerAt = vehicleAt - 1
        if (words[ownerAt].equals("laws", ignoreCase = true) && ownerAt >= 1 && words[ownerAt - 1].equals("in", ignoreCase = true)) {
            words.removeAt(ownerAt)
            words[ownerAt - 1] = "In-Laws'"
        } else {
            possessive(words[ownerAt])?.let { words[ownerAt] = it }
        }
    }
    return words.joinToString(" ") { word ->
        if (word.lowercase() in UPPERCASE_WORDS) word.uppercase() else word.replaceFirstChar(Char::uppercase)
    }
}

/** "andrews" -> "Andrew's", "james" -> "James's", "parents" -> "Parents'"; null when it can't tell. */
private fun possessive(owner: String): String? {
    val lower = owner.lowercase()
    if (lower.length < 3 || !lower.endsWith('s')) return null
    val capitalised = owner.replaceFirstChar(Char::uppercase)
    return when {
        lower in PLURAL_OWNERS -> "$capitalised'"
        lower in NAMES_ENDING_IN_S -> "$capitalised's"
        lower.dropLast(1) in NAMES_ENDING_IN_S -> "${capitalised.dropLast(1)}'s"
        lower.endsWith("ss") -> null
        else -> "${capitalised.dropLast(1)}'s"
    }
}

/** "front_lawn" -> "front lawn", lower case, ready to follow "the". */
fun zoneDisplayName(key: String): String =
    key.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.lowercase() }

/**
 * "in the driveway" / "on the front lawn" / "on the sidewalk". Enclosed or entered places take
 * "in"; surfaces take "on". Good enough for yard vocabulary; a wrong guess still reads.
 */
fun zonePhrase(key: String): String {
    val name = zoneDisplayName(key)
    val enclosed = listOf("driveway", "garage", "carport", "yard", "porch", "garden", "pool", "patio", "alley", "hallway", "kitchen", "room")
    val preposition = if (enclosed.any { it in name }) "in" else "on"
    return "$preposition the $name"
}
