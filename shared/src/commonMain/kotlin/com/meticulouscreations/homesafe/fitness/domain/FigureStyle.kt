package com.meticulouscreations.homesafe.fitness.domain

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_figure_forged_blurb
import homesafe.shared.generated.resources.fitness_figure_forged_label
import homesafe.shared.generated.resources.fitness_figure_stone_blurb
import homesafe.shared.generated.resources.fitness_figure_stone_label
import homesafe.shared.generated.resources.fitness_figure_true_3d_blurb
import homesafe.shared.generated.resources.fitness_figure_true_3d_label
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource

/**
 * How the figure that performs an exercise is drawn. All three show the same movement
 * ([MovementKind]) and the same muscles at work; what changes is the look, and where the
 * picture comes from.
 */
enum class FigureStyle(val label: StringResource, val blurb: StringResource) {
    /** A modelled lifter rendered ahead of time, a rep as a sheet of frames played back, the working muscles laid over it as light. */
    TRUE_3D(Res.string.fitness_figure_true_3d_label, Res.string.fitness_figure_true_3d_blurb),

    /** A figure of pale stone, drawn live from the jointed rig. */
    STONE(Res.string.fitness_figure_stone_label, Res.string.fitness_figure_stone_blurb),

    /** An anatomy study in dark iron, drawn live from the same rig. Also what stands in where a movement's frames can't be had. */
    FORGED(Res.string.fitness_figure_forged_label, Res.string.fitness_figure_forged_blurb),
    ;

    companion object {
        val DEFAULT = TRUE_3D

        /** A stored name this build doesn't know (a downgrade after a newer one added a style) falls back to the default. */
        fun fromName(name: String?): FigureStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The fitness app's own preferences, kept on the device. They are this install's, and no part of the training log or a copy of it. */
interface FitnessPreferencesRepository {
    fun observeFigureStyle(): Flow<FigureStyle>

    suspend fun setFigureStyle(style: FigureStyle)
}
