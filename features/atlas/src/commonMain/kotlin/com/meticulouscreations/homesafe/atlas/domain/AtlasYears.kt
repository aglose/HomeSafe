package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable

/**
 * What a choice of years adds up to: the places visited in any of them and how many regions (a
 * country's states or provinces) those are in. A stay belongs to the year it began in, as in
 * [AtlasAnalysis]. The years needn't be next to each other: 2018 alone, or 2016 and 2020.
 */
@Immutable
class YearsCovered(val placeKeys: Set<String>, val regions: Int) {
    companion object {
        fun of(history: AtlasHistory, years: Set<Int>): YearsCovered = YearsIndex.of(history).covered(years)
    }
}

/**
 * What [YearsCovered] is worked out from, worked out once: the places visited each year, and the
 * region each place is in. Finding a place's town means looking round it, too slow to do for
 * every place each time a year is picked on the map, or each step while the years play.
 */
@Immutable
class YearsIndex(private val keysByYear: Map<Int, Set<String>>, private val regionOf: Map<String, String>) {
    fun covered(years: Set<Int>): YearsCovered {
        val keys = HashSet<String>()
        years.forEach { year -> keysByYear[year]?.let { keys.addAll(it) } }
        val regions = keys.mapNotNullTo(HashSet()) { regionOf[it] }
        return YearsCovered(keys, regions.size)
    }

    companion object {
        val Empty = YearsIndex(emptyMap(), emptyMap())

        fun of(history: AtlasHistory): YearsIndex {
            val keysByYear = HashMap<Int, HashSet<String>>()
            history.visits.forEach { keysByYear.getOrPut(CivilDate.of(it.startDay).year) { HashSet() }.add(it.placeKey) }
            val regionOf = HashMap<String, String>()
            history.places.forEach { (key, place) ->
                history.locality(place)?.let { locality -> locality.region?.let { regionOf[key] = "${locality.country}-$it" } }
            }
            return YearsIndex(keysByYear, regionOf)
        }
    }
}
