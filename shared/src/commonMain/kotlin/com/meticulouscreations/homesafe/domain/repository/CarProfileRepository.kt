package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles

/**
 * What the household's cars look like (see [CarProfile]), kept by the push relay, which checks the
 * known-cars classifier's names against them. The relay owns it; nothing is cached here.
 */
interface CarProfileRepository {
    suspend fun getProfiles(): Result<CarProfiles>

    /** Answers the profile as the relay keeps it. */
    suspend fun saveProfile(profile: CarProfile): Result<CarProfile>
}
