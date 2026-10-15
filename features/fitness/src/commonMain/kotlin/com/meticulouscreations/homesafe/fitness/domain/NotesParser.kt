package com.meticulouscreations.homesafe.fitness.domain

import kotlin.math.roundToInt

/** How the weight on a line of the notes was written, which says what kind of load it is. */
enum class ParsedLoad {
    /** Pounds (or kilograms, converted). */
    POUNDS,

    /** "4 plates + 25 lbs": 45 lb plates a side, with anything extra a side, kept as the total. */
    PLATES,

    /** A bare number before the reps: a machine's pin. */
    LEVEL,

    /** Reps alone. */
    NONE,
}

/** One line of reps read out of the notes. */
data class ParsedSet(
    val weight: Double,
    val reps: Int,
    val load: ParsedLoad,
    /** Written "× 2": a pair of dumbbells, the weight being one of them. */
    val pair: Boolean = false,
    val bodyweight: Double? = null,
    val note: String = "",
    /** When it was done, where the notes say (a "Philly Sept 2021" heading); null for the undated ladder. */
    val epochSeconds: Long? = null,
    /** Marked "recently": where the lifter stands now, as against all time. */
    val recent: Boolean = false,
)

/**
 * An exercise as the notes have it: its name, the heading it sat under if that named a body
 * part, its sets, and [openWeights], the weights written down with the reps still blank: the
 * next rung, waiting to be done.
 */
data class ParsedExercise(
    val name: String,
    val section: BodyPart?,
    val sets: List<ParsedSet>,
    val openWeights: List<Double> = emptyList(),
)

data class ParsedNotes(val exercises: List<ParsedExercise>, val skipped: List<String> = emptyList()) {
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

/**
 * Reads workout notes as they are kept in a notes app: a name on a line of its own, and under
 * it one line for each weight with the most reps done at it.
 *
 * ```
 * Hack squat
 * - 290lbs - 12 reps
 * - 320lbs (3plates+25lbs)- 10 reps
 * ```
 *
 * It is written against real notes, so it takes what they do: dumbbells as `44lbs x 2`, plate
 * counts (`4 plates + 25lbs`), a machine's pin instead of a weight (`14 - 29 reps`), reps first
 * (`29 reps - 55 lbs`), reps alone, a bodyweight beside a weighted pull-up, remarks after the
 * numbers, a weight with its reps still blank, headings that are a body part (`Tris`) or a place
 * and month (`Philly Sept 2021`), and whole sets on one line (`Lat pulldown - 225lbs 4 reps`).
 * A line it can make nothing of is handed back in [ParsedNotes.skipped] instead of guessed at.
 */
object NotesParser {
    fun parse(text: String, defaultPart: BodyPart? = null): ParsedNotes {
        val builders = LinkedHashMap<String, Builder>()
        val skipped = ArrayList<String>()
        var section = defaultPart
        var date: Long? = null
        var current: Builder? = null
        var previousName: String? = null

        fun exercise(rawName: String): Builder {
            val name = cleanName(rawName, previousName)
            previousName = name
            return builders.getOrPut((section?.name ?: "") + "/" + name.lowercase()) { Builder(name, section) }
        }

        for (raw in text.lines()) {
            val line = raw.trim().trimStart('-', '•', '*', '–', '—').trim()
            if (line.isEmpty()) continue
            val entry = parseEntry(line)
            when {
                entry == null -> when (val heading = heading(line)) {
                    is Heading.Part -> {
                        section = heading.part
                        current = null
                    }

                    is Heading.Dated -> {
                        date = heading.epochSeconds
                        section = defaultPart
                        current = null
                    }

                    Heading.Title -> {
                        date = null
                        section = defaultPart
                        current = null
                    }

                    Heading.Exercise -> current = exercise(line)
                }

                entry.name != null -> current = exercise(entry.name).also { it.add(entry, date) }

                current != null -> current.add(entry, date)

                else -> skipped += raw.trim()
            }
        }
        // A heading with nothing under it reads the same as a stray line of prose: it is handed back, not made an exercise.
        val (read, bare) = builders.values.partition { it.sets.isNotEmpty() || it.open.isNotEmpty() }
        return ParsedNotes(read.map { it.build() }, skipped + bare.map { it.name })
    }

    private class Builder(val name: String, val section: BodyPart?) {
        val sets = ArrayList<ParsedSet>()
        val open = ArrayList<Double>()

        fun add(entry: Entry, date: Long?) {
            val reps = entry.reps
            if (reps == null) {
                entry.weight?.let { open += it }
            } else {
                sets += ParsedSet(entry.weight ?: 0.0, reps, entry.load, entry.pair, entry.bodyweight, entry.note, date, entry.recent)
            }
        }

        fun build() = ParsedExercise(name, section, sets.toList(), open.toList())
    }

    private class Entry(
        val name: String?,
        val weight: Double?,
        val reps: Int?,
        val load: ParsedLoad,
        val pair: Boolean,
        val bodyweight: Double?,
        val note: String,
        val recent: Boolean,
    )

    private sealed interface Heading {
        data class Part(val part: BodyPart?) : Heading

        data class Dated(val epochSeconds: Long) : Heading

        /** A note's own title, or anything else that isn't an exercise: what follows starts afresh. */
        data object Title : Heading

        data object Exercise : Heading
    }

    private fun heading(line: String): Heading {
        val words = line.lowercase().replace(Regex("[^a-z0-9& ]"), " ").trim().replace(Regex("\\s+"), " ")
        PARTS[words]?.let { return Heading.Part(it) }
        if (words in MIXED_PARTS) return Heading.Part(null)
        monthAndYear(words)?.let { return Heading.Dated(it) }
        if (words in TITLES) return Heading.Title
        return Heading.Exercise
    }

    /**
     * A heading with a month and a year in it ("Philly Sept 2021", "Oahu Nov 2021", "Philly
     * June 22"), as the middle of that month. Two digits after a month are a year when they
     * could be one (20 and up): these are headings over a trip's worth of sets, not diary dates.
     */
    private fun monthAndYear(words: String): Long? {
        val tokens = words.split(' ')
        val monthIndex = tokens.indexOfFirst { it in MONTHS }
        if (monthIndex < 0) return null
        val month = MONTHS.getValue(tokens[monthIndex])
        val year = tokens.drop(monthIndex + 1).firstNotNullOfOrNull { token ->
            val number = token.toIntOrNull() ?: return@firstNotNullOfOrNull null
            when {
                number in 1990..2100 -> number
                token.length == 2 && number in 20..99 -> 2000 + number
                else -> null
            }
        } ?: return null
        return epochDay(year, month, 15) * SECONDS_PER_DAY + SECONDS_PER_DAY / 2
    }

    /** Days from 1970-01-01 to the given civil date (Howard Hinnant's `days_from_civil`). */
    internal fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** [line] as a set, with the exercise's name when the line carries one; null when it is a heading. */
    private fun parseEntry(line: String): Entry? {
        var s = line
        var bodyweight: Double? = null
        (BODYWEIGHT_AFTER.find(s) ?: BODYWEIGHT_BEFORE.find(s))?.let { match ->
            bodyweight = match.groupValues[1].toDoubleOrNull()
            s = s.removeRange(match.range)
        }
        val asides = PARENTHESES.findAll(s).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        s = PARENTHESES.replace(s, " ")

        // Where the name stops and the numbers start, for a line that opens with words ("45 degree row" opens with words too).
        var name: String? = null
        val leading = LEADING_NUMBER.find(s)
        val opensWithName = s.firstOrNull()?.isLetter() == true ||
            (leading != null && leading.groupValues[2].isNotEmpty() && leading.groupValues[2].lowercase() !in SET_WORDS)
        if (opensWithName) {
            val from = if (leading != null && s.first().isDigit()) leading.groupValues[1].length else 0
            val start = listOfNotNull(
                WEIGHT.find(s, from)?.range?.first,
                PLATES.find(s, from)?.range?.first,
                REPS.find(s, from)?.range?.first,
            ).minOrNull() ?: return null
            name = s.substring(0, start).trim().trimEnd('-', ':', '–').trim()
            if (name.isEmpty()) return null
            s = s.substring(start)
        }

        var pair = false
        PAIR.find(s)?.let {
            pair = true
            s = s.removeRange(it.range)
        }
        var weight: Double? = null
        var load = ParsedLoad.NONE
        PLATES.find(s)?.let { match ->
            val plates = match.groupValues[1].toDouble()
            val extra = match.groupValues[2].toDoubleOrNull() ?: 0.0
            weight = (plates * PLATE_POUNDS + extra) * 2
            load = ParsedLoad.PLATES
            s = s.removeRange(match.range)
        }
        if (weight == null) {
            WEIGHT.find(s)?.let { match ->
                val value = match.groupValues[1].toDouble()
                weight = if (match.groupValues[2].lowercase().startsWith("k")) (value * POUNDS_PER_KILOGRAM * 2).roundToInt() / 2.0 else value
                load = ParsedLoad.POUNDS
                s = s.removeRange(match.range)
            }
        }
        var reps: Int? = null
        REPS.find(s)?.let { match ->
            reps = match.groupValues[1].toIntOrNull()
            s = s.removeRange(match.range)
        }
        // "80lbs - reps": the word with no number is a blank still to fill.
        s = s.replace(Regex("\\breps?\\b", RegexOption.IGNORE_CASE), " ")
        val bare = NUMBER.findAll(s).map { it.value }.toList()
        // Numbers with no word beside them are whichever of the two the line is still missing.
        if (bare.isNotEmpty() && (reps == null || weight == null)) {
            when {
                reps == null && weight != null -> reps = bare.last().toDoubleOrNull()?.toInt()

                reps == null && bare.size == 1 -> reps = bare[0].toDoubleOrNull()?.toInt()

                reps == null -> {
                    weight = bare[0].toDoubleOrNull()
                    load = ParsedLoad.LEVEL
                    reps = bare[1].toDoubleOrNull()?.toInt()
                }

                else -> {
                    weight = bare[0].toDoubleOrNull()
                    load = ParsedLoad.LEVEL
                }
            }
            s = NUMBER.replace(s, " ")
        }
        if (weight == null && reps == null) return null
        val remark = (listOf(s.replace(Regex("[-–:,+]"), " ").trim().replace(Regex("\\s+"), " ")) + asides).filter { it.isNotEmpty() }.joinToString(", ")
        return Entry(name, weight, reps?.takeIf { it > 0 }, load, pair, bodyweight, remark, recent = remark.contains("recent", ignoreCase = true))
    }

    /**
     * A heading tidied into a name: "Max pull-ups" is the pull-ups it is a record of, and "Other
     * machine", written under another exercise, is that exercise on the other machine.
     */
    private fun cleanName(raw: String, previous: String?): String {
        var name = raw.trim().trimEnd(':').replace(Regex("\\s+"), " ")
        if (name.startsWith("max ", ignoreCase = true)) name = name.substring(4)
        if (previous != null && name.startsWith("other ", ignoreCase = true)) {
            name = previous.substringBefore(" (").trim() + " (" + name.lowercase() + ")"
        }
        return name.replaceFirstChar { it.uppercase() }
    }

    private const val PLATE_POUNDS = 45.0
    private const val POUNDS_PER_KILOGRAM = 2.20462

    private val WEIGHT = Regex("(\\d+(?:\\.\\d+)?)\\s*(lbs?|kgs?|#)(?![a-z])", RegexOption.IGNORE_CASE)
    private val PLATES = Regex("(\\d+)\\s*plates?(?:\\s*\\+?\\s*(\\d+(?:\\.\\d+)?)\\s*lbs?)?", RegexOption.IGNORE_CASE)
    private val REPS = Regex("(\\d+)\\s*\\+?\\s*reps?\\b", RegexOption.IGNORE_CASE)
    private val PAIR = Regex("\\bx\\s*2\\b", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val LEADING_NUMBER = Regex("^(\\d+(?:\\.\\d+)?\\s*)([a-zA-Z]*)")
    private val PARENTHESES = Regex("\\(([^)]*)\\)")
    private val BODYWEIGHT_AFTER = Regex("\\(?\\s*-?\\s*(?:body\\s?weight|\\bbw\\b)\\s*:?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:lbs?)?\\s*\\)?", RegexOption.IGNORE_CASE)
    private val BODYWEIGHT_BEFORE = Regex("\\(?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:lbs?)?\\s*(?:body\\s?weight|\\bbw\\b)\\s*\\)?", RegexOption.IGNORE_CASE)

    /** Words that follow a number when the number is a set's and not the start of a name. */
    private val SET_WORDS = setOf("lb", "lbs", "kg", "kgs", "plate", "plates", "rep", "reps", "x", "all", "recent", "recently", "ish", "each", "per", "bodyweight", "bw")

    private val PARTS = mapOf(
        "chest" to BodyPart.CHEST,
        "back" to BodyPart.BACK,
        "legs" to BodyPart.LEGS,
        "leg day" to BodyPart.LEGS,
        "shoulders" to BodyPart.SHOULDERS,
        "delts" to BodyPart.SHOULDERS,
        "tris" to BodyPart.TRICEPS,
        "triceps" to BodyPart.TRICEPS,
        "bis" to BodyPart.BICEPS,
        "biceps" to BodyPart.BICEPS,
        "abs" to BodyPart.CORE,
        "core" to BodyPart.CORE,
    )

    /** A heading over more than one shelf: each exercise under it is placed by its own name, or by the next heading. */
    private val MIXED_PARTS = setOf("bis & tris", "bis and tris", "tris & bis", "arms", "push", "pull")
    private val TITLES = setOf("personal records", "records", "prs", "gym", "workout", "workouts", "notes")
    private val MONTHS: Map<String, Int> = listOf(
        listOf("jan", "january"),
        listOf("feb", "february"),
        listOf("mar", "march"),
        listOf("apr", "april"),
        listOf("may"),
        listOf("jun", "june"),
        listOf("jul", "july"),
        listOf("aug", "august"),
        listOf("sep", "sept", "september"),
        listOf("oct", "october"),
        listOf("nov", "november"),
        listOf("dec", "december"),
    ).flatMapIndexed { index, names -> names.map { it to index + 1 } }.toMap()
}
