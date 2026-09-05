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

/** "sarahs_tesla" -> "Sarah's Tesla"; "andrew" -> "Andrew"; "delivery_van" -> "Delivery Van". */
fun subLabelDisplayName(key: String): String =
    KNOWN_SUB_LABELS[key.lowercase()] ?: key.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

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
