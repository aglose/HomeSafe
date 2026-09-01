package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Intentionally has no Room-backed cache: there is no real remote Frigate events API integrated
 * yet, so this repository's in-memory sample data IS the source of truth. Adding a Room table
 * here would cache static mock data for no benefit — do this only once a real events API backs it.
 */
@Inject
@SingleIn(AppScope::class)
class MomentsRepositoryImpl : MomentsRepository {

    private val sampleMoments = listOf(
        MomentEvent(
            id = "moment-1",
            title = "Person at Front Door",
            cameraName = "Front Porch Camera",
            timestamp = "08:42 AM",
            durationLabel = "0:15",
            dateGroup = "Today",
            dateSubLabel = "Oct 24",
            category = MomentCategory.PEOPLE,
            badgeLabel = "Person",
        ),
        MomentEvent(
            id = "moment-2",
            title = "Vehicle in Driveway",
            cameraName = "Driveway Camera",
            timestamp = "06:15 AM",
            durationLabel = "0:42",
            dateGroup = "Today",
            dateSubLabel = "Oct 24",
            category = MomentCategory.VEHICLES,
            badgeLabel = "Vehicle",
        ),
        MomentEvent(
            id = "moment-3",
            title = "Animal Detected",
            cameraName = "Backyard Camera",
            timestamp = "11:45 PM",
            durationLabel = null,
            dateGroup = "Yesterday",
            dateSubLabel = "Oct 23",
            category = MomentCategory.ANIMALS,
            badgeLabel = "Animal",
        ),
    )

    override fun observeMoments(): Flow<List<MomentEvent>> = flowOf(sampleMoments)
}
