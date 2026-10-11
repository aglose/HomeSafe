package com.meticulouscreations.homesafe.ui.notification

import androidx.annotation.DrawableRes
import com.meticulouscreations.homesafe.shared.R

/**
 * [NotificationGlyph] as the vector resource Android's status bar draws: the files written from
 * the glyphs themselves (see `NotificationGlyphsTest`). A `when` with no `else`, so a glyph with
 * no drawable doesn't compile.
 */
@get:DrawableRes
val NotificationGlyph.drawableRes: Int
    get() = when (this) {
        NotificationGlyph.RAIN -> R.drawable.ic_notification_weather_rain
        NotificationGlyph.SNOW -> R.drawable.ic_notification_weather_snow
        NotificationGlyph.MIX -> R.drawable.ic_notification_weather_mix
        NotificationGlyph.STORM -> R.drawable.ic_notification_weather_storm
        NotificationGlyph.FOG -> R.drawable.ic_notification_weather_fog
        NotificationGlyph.WIND -> R.drawable.ic_notification_weather_wind
        NotificationGlyph.SUN -> R.drawable.ic_notification_weather_sun
        NotificationGlyph.HEAT -> R.drawable.ic_notification_weather_heat
        NotificationGlyph.COLD -> R.drawable.ic_notification_weather_cold
        NotificationGlyph.FREEZE -> R.drawable.ic_notification_weather_freeze
        NotificationGlyph.WARMER -> R.drawable.ic_notification_weather_warmer
        NotificationGlyph.COLDER -> R.drawable.ic_notification_weather_colder
        NotificationGlyph.AIR -> R.drawable.ic_notification_weather_air
        NotificationGlyph.TORNADO -> R.drawable.ic_notification_weather_tornado
        NotificationGlyph.FLOOD -> R.drawable.ic_notification_weather_flood
        NotificationGlyph.HURRICANE -> R.drawable.ic_notification_weather_hurricane
        NotificationGlyph.FIRE -> R.drawable.ic_notification_weather_fire
        NotificationGlyph.WARNING -> R.drawable.ic_notification_weather_warning
        NotificationGlyph.CAMERA -> R.drawable.ic_notification_detection
        NotificationGlyph.PERSON -> R.drawable.ic_notification_person
        NotificationGlyph.CAR -> R.drawable.ic_notification_car
        NotificationGlyph.ANIMAL -> R.drawable.ic_notification_animal
        NotificationGlyph.AWAY -> R.drawable.ic_notification_away
        NotificationGlyph.SUMMARY -> R.drawable.ic_notification_summary
        NotificationGlyph.BUDGET -> R.drawable.ic_notification_budget
        NotificationGlyph.DONE -> R.drawable.ic_notification_done
    }
