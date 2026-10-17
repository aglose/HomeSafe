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
        fun of(history: AtlasHistory, years: Set<Int>): YearsCovered {
            val keys = HashSet<String>()
            history.visits.forEach { if (CivilDate.of(it.startDay).year in years) keys += it.placeKey }
            val regions = keys.mapNotNullTo(HashSet()) { key ->
                history.locality(history.place(key))?.let { locality -> locality.region?.let { "${locality.country}-$it" } }
            }
            return YearsCovered(keys, regions.size)
        }
    }
}
