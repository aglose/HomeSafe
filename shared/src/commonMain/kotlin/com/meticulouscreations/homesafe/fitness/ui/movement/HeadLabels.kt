package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.fitness.ui.FitnessTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_head_adductor_longus
import homesafe.shared.generated.resources.fitness_head_adductor_magnus
import homesafe.shared.generated.resources.fitness_head_biceps_femoris_long
import homesafe.shared.generated.resources.fitness_head_biceps_femoris_short
import homesafe.shared.generated.resources.fitness_head_biceps_long
import homesafe.shared.generated.resources.fitness_head_biceps_short
import homesafe.shared.generated.resources.fitness_head_brachialis
import homesafe.shared.generated.resources.fitness_head_brachioradialis
import homesafe.shared.generated.resources.fitness_head_deltoid_anterior
import homesafe.shared.generated.resources.fitness_head_deltoid_lateral
import homesafe.shared.generated.resources.fitness_head_deltoid_posterior
import homesafe.shared.generated.resources.fitness_head_erector_spinae
import homesafe.shared.generated.resources.fitness_head_extensor_carpi_radialis
import homesafe.shared.generated.resources.fitness_head_extensor_carpi_ulnaris
import homesafe.shared.generated.resources.fitness_head_extensor_digitorum
import homesafe.shared.generated.resources.fitness_head_extensor_digitorum_longus
import homesafe.shared.generated.resources.fitness_head_external_oblique
import homesafe.shared.generated.resources.fitness_head_flexor_carpi_ulnaris
import homesafe.shared.generated.resources.fitness_head_gastrocnemius_lateral
import homesafe.shared.generated.resources.fitness_head_gastrocnemius_medial
import homesafe.shared.generated.resources.fitness_head_gluteus_maximus
import homesafe.shared.generated.resources.fitness_head_gluteus_medius
import homesafe.shared.generated.resources.fitness_head_gracilis
import homesafe.shared.generated.resources.fitness_head_infraspinatus
import homesafe.shared.generated.resources.fitness_head_latissimus_dorsi
import homesafe.shared.generated.resources.fitness_head_pectoralis_clavicular
import homesafe.shared.generated.resources.fitness_head_pectoralis_sternal
import homesafe.shared.generated.resources.fitness_head_peroneus_longus
import homesafe.shared.generated.resources.fitness_head_rectus_abdominis
import homesafe.shared.generated.resources.fitness_head_rectus_femoris
import homesafe.shared.generated.resources.fitness_head_sartorius
import homesafe.shared.generated.resources.fitness_head_semimembranosus
import homesafe.shared.generated.resources.fitness_head_semitendinosus
import homesafe.shared.generated.resources.fitness_head_serratus_anterior
import homesafe.shared.generated.resources.fitness_head_soleus
import homesafe.shared.generated.resources.fitness_head_sternocleidomastoid
import homesafe.shared.generated.resources.fitness_head_tensor_fasciae_latae
import homesafe.shared.generated.resources.fitness_head_teres_major
import homesafe.shared.generated.resources.fitness_head_teres_minor
import homesafe.shared.generated.resources.fitness_head_tibialis_anterior
import homesafe.shared.generated.resources.fitness_head_trapezius_lower
import homesafe.shared.generated.resources.fitness_head_trapezius_middle
import homesafe.shared.generated.resources.fitness_head_trapezius_upper
import homesafe.shared.generated.resources.fitness_head_triceps_lateral
import homesafe.shared.generated.resources.fitness_head_triceps_long
import homesafe.shared.generated.resources.fitness_head_triceps_medial
import homesafe.shared.generated.resources.fitness_head_vastus_lateralis
import homesafe.shared.generated.resources.fitness_head_vastus_medialis
import homesafe.shared.generated.resources.fitness_head_wrist_flexors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A head's name, as an anatomy text gives it (strings_fitness_movement.xml). Where the name has
 * two lines, the first is the muscle and the second which part of it.
 */
internal val HeadId.label: StringResource
    get() = when (this) {
        HeadId.STERNOCLEIDOMASTOID -> Res.string.fitness_head_sternocleidomastoid
        HeadId.TRAPEZIUS_UPPER -> Res.string.fitness_head_trapezius_upper
        HeadId.TRAPEZIUS_MIDDLE -> Res.string.fitness_head_trapezius_middle
        HeadId.TRAPEZIUS_LOWER -> Res.string.fitness_head_trapezius_lower
        HeadId.DELTOID_ANTERIOR -> Res.string.fitness_head_deltoid_anterior
        HeadId.DELTOID_LATERAL -> Res.string.fitness_head_deltoid_lateral
        HeadId.DELTOID_POSTERIOR -> Res.string.fitness_head_deltoid_posterior
        HeadId.PECTORALIS_CLAVICULAR -> Res.string.fitness_head_pectoralis_clavicular
        HeadId.PECTORALIS_STERNAL -> Res.string.fitness_head_pectoralis_sternal
        HeadId.SERRATUS_ANTERIOR -> Res.string.fitness_head_serratus_anterior
        HeadId.RECTUS_ABDOMINIS -> Res.string.fitness_head_rectus_abdominis
        HeadId.EXTERNAL_OBLIQUE -> Res.string.fitness_head_external_oblique
        HeadId.LATISSIMUS_DORSI -> Res.string.fitness_head_latissimus_dorsi
        HeadId.TERES_MAJOR -> Res.string.fitness_head_teres_major
        HeadId.TERES_MINOR -> Res.string.fitness_head_teres_minor
        HeadId.INFRASPINATUS -> Res.string.fitness_head_infraspinatus
        HeadId.ERECTOR_SPINAE -> Res.string.fitness_head_erector_spinae
        HeadId.BICEPS_LONG -> Res.string.fitness_head_biceps_long
        HeadId.BICEPS_SHORT -> Res.string.fitness_head_biceps_short
        HeadId.BRACHIALIS -> Res.string.fitness_head_brachialis
        HeadId.TRICEPS_LONG -> Res.string.fitness_head_triceps_long
        HeadId.TRICEPS_LATERAL -> Res.string.fitness_head_triceps_lateral
        HeadId.TRICEPS_MEDIAL -> Res.string.fitness_head_triceps_medial
        HeadId.BRACHIORADIALIS -> Res.string.fitness_head_brachioradialis
        HeadId.WRIST_FLEXORS -> Res.string.fitness_head_wrist_flexors
        HeadId.FLEXOR_CARPI_ULNARIS -> Res.string.fitness_head_flexor_carpi_ulnaris
        HeadId.EXTENSOR_CARPI_RADIALIS -> Res.string.fitness_head_extensor_carpi_radialis
        HeadId.EXTENSOR_DIGITORUM -> Res.string.fitness_head_extensor_digitorum
        HeadId.EXTENSOR_CARPI_ULNARIS -> Res.string.fitness_head_extensor_carpi_ulnaris
        HeadId.GLUTEUS_MEDIUS -> Res.string.fitness_head_gluteus_medius
        HeadId.GLUTEUS_MAXIMUS -> Res.string.fitness_head_gluteus_maximus
        HeadId.TENSOR_FASCIAE_LATAE -> Res.string.fitness_head_tensor_fasciae_latae
        HeadId.SARTORIUS -> Res.string.fitness_head_sartorius
        HeadId.RECTUS_FEMORIS -> Res.string.fitness_head_rectus_femoris
        HeadId.VASTUS_LATERALIS -> Res.string.fitness_head_vastus_lateralis
        HeadId.VASTUS_MEDIALIS -> Res.string.fitness_head_vastus_medialis
        HeadId.ADDUCTOR_LONGUS -> Res.string.fitness_head_adductor_longus
        HeadId.ADDUCTOR_MAGNUS -> Res.string.fitness_head_adductor_magnus
        HeadId.GRACILIS -> Res.string.fitness_head_gracilis
        HeadId.BICEPS_FEMORIS_LONG -> Res.string.fitness_head_biceps_femoris_long
        HeadId.BICEPS_FEMORIS_SHORT -> Res.string.fitness_head_biceps_femoris_short
        HeadId.SEMITENDINOSUS -> Res.string.fitness_head_semitendinosus
        HeadId.SEMIMEMBRANOSUS -> Res.string.fitness_head_semimembranosus
        HeadId.TIBIALIS_ANTERIOR -> Res.string.fitness_head_tibialis_anterior
        HeadId.EXTENSOR_DIGITORUM_LONGUS -> Res.string.fitness_head_extensor_digitorum_longus
        HeadId.PERONEUS_LONGUS -> Res.string.fitness_head_peroneus_longus
        HeadId.GASTROCNEMIUS_LATERAL -> Res.string.fitness_head_gastrocnemius_lateral
        HeadId.GASTROCNEMIUS_MEDIAL -> Res.string.fitness_head_gastrocnemius_medial
        HeadId.SOLEUS -> Res.string.fitness_head_soleus
    }

/** The type the names are set in beside a figure: small, since there are a dozen of them round one lifter. */
@Composable
internal fun headNameStyle(): TextStyle = FitnessTheme.type.label.copy(fontSize = 9.5.sp, lineHeight = 11.sp)

/**
 * The names of [heads] in the reader's language, for a figure to call them out with. A figure
 * keeps what it has measured of the names it was first given, so one drawn with these is keyed
 * on them (see [ExerciseFigure]): another language, or names that arrive late on the web, are a
 * new figure. A head outside [heads] has no name here, and is given an empty one.
 */
@Composable
internal fun rememberHeadNames(heads: Set<HeadId>): HeadNames {
    val style = headNameStyle()
    val names = HashMap<HeadId, String>(heads.size)
    for (head in HeadId.entries) {
        if (head in heads) key(head) { names[head] = stringResource(head.label) }
    }
    return remember(style, names) { HeadNames(style) { names[it].orEmpty() } }
}
